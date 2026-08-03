package com.example.ecapi.security.mfa;

import com.example.ecapi.domain.User;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.repository.UserRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 二段階認証（TOTP）の登録・解除・検証。
 *
 * <h2>登録を3段にしている理由</h2>
 * ①鍵を発行 → ②認証アプリに登録 → <strong>③コードを1回通して初めて有効化</strong>。
 * 鍵を作った時点で有効にすると、②に失敗した人（QRを読めなかった、別の端末に登録した）が
 * その場で締め出される。しかも本人には何が起きたか分からない。
 * 「1回通せた」＝「次回も通せる」の確認が取れてから有効にする。
 *
 * <h2>リカバリコード</h2>
 * 端末を失くしたときの最後の入口。<strong>ハッシュで保存し、平文は発行時に1度だけ返す</strong>。
 * 平文で持つと、DBが読まれた時点で二段階目が素通りになる＝パスワードと同じ扱いが要る。
 * 1つ使うたびにその行を消す（使い捨て）。
 */
@Service
public class MfaService {

    /** 発行するリカバリコードの本数。多すぎると管理できず、少なすぎると足りない。 */
    private static final int RECOVERY_CODE_COUNT = 10;
    /** 紛らわしい文字（0/O・1/I）を除いた英数字。紙に書き写す前提なので。 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String issuer;

    public MfaService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                      @Value("${app.mfa.issuer:EC API}") String issuer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.issuer = issuer;
    }

    /** 登録の1段目。鍵を作って保存するが、<strong>まだ有効にしない</strong>。 */
    @Transactional
    public Enrollment startEnrollment(User user) {
        if (user.isMfaEnabled()) {
            throw new BadRequestException("二段階認証はすでに有効です。設定し直すには一度解除してください。");
        }
        String secret = Totp.generateSecret();
        user.setMfaSecret(secret);
        user.setMfaEnabled(false);
        userRepository.save(user);
        return new Enrollment(secret, Totp.uri(secret, user.getEmail(), issuer));
    }

    /**
     * 登録の3段目。コードが通ったら有効化し、リカバリコードを<strong>この一度だけ</strong>返す。
     *
     * @return 平文のリカバリコード（保存はハッシュ。二度と取り出せない）
     */
    @Transactional
    public List<String> confirmEnrollment(User user, String code) {
        if (user.isMfaEnabled()) {
            throw new BadRequestException("二段階認証はすでに有効です。");
        }
        if (user.getMfaSecret() == null || user.getMfaSecret().isBlank()) {
            throw new BadRequestException("先に二段階認証の設定を開始してください。");
        }
        if (!Totp.verify(user.getMfaSecret(), code)) {
            throw new BadRequestException("認証コードが違います。認証アプリの表示と端末の時刻を確認してください。");
        }

        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<String> hashed = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String recovery = randomRecoveryCode();
            plain.add(recovery);
            hashed.add(passwordEncoder.encode(recovery));
        }
        user.setMfaRecoveryCodes(String.join("\n", hashed));
        user.setMfaEnabled(true);
        userRepository.save(user);
        return plain;
    }

    /**
     * 解除。<strong>解除にもコードを要求する</strong>——盗まれたセッションで黙って外されると、
     * 二段階認証を入れた意味が無くなる。リカバリコードでも解除できる（端末を失くした場合）。
     */
    @Transactional
    public void disable(User user, String code) {
        if (!user.isMfaEnabled()) {
            throw new BadRequestException("二段階認証は有効になっていません。");
        }
        if (!verifyCode(user, code)) {
            throw new BadRequestException("認証コードが違います。");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaRecoveryCodes(null);
        userRepository.save(user);
    }

    /**
     * ログインの二段階目。TOTP を先に試し、外れたときだけリカバリコードを照合する。
     *
     * <p>順序に意味がある。リカバリコードの照合は本数ぶんのハッシュ計算が要るので、
     * 通常のログイン（TOTP が通る）でその費用を払わせない。
     *
     * @return 通ったら true。リカバリコードで通った場合、そのコードは消費される
     */
    @Transactional
    public boolean verifyCode(User user, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        if (Totp.verify(user.getMfaSecret(), code)) {
            return true;
        }
        return consumeRecoveryCode(user, code.trim());
    }

    /** 残っているリカバリコードの本数（画面に「あと何本」と出すため。値は出さない）。 */
    public int remainingRecoveryCodes(User user) {
        String stored = user.getMfaRecoveryCodes();
        if (stored == null || stored.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(stored.split("\n")).filter(s -> !s.isBlank()).count();
    }

    private boolean consumeRecoveryCode(User user, String code) {
        String stored = user.getMfaRecoveryCodes();
        if (stored == null || stored.isBlank()) {
            return false;
        }
        List<String> remaining = new ArrayList<>();
        boolean used = false;
        for (String hash : stored.split("\n")) {
            if (hash.isBlank()) {
                continue;
            }
            // 使い捨て。1本しか消さない（同じコードで何度も入られないように）
            if (!used && passwordEncoder.matches(code, hash)) {
                used = true;
                continue;
            }
            remaining.add(hash);
        }
        if (used) {
            user.setMfaRecoveryCodes(String.join("\n", remaining));
            userRepository.save(user);
        }
        return used;
    }

    /** {@code ABCD-EFGH} 形式。紙に書き写す前提なので区切りを入れる。 */
    private static String randomRecoveryCode() {
        StringBuilder out = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                out.append('-');
            }
            out.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return out.toString();
    }

    /** 登録開始の結果。QR画像はサーバーで作らず、URI をフロントで描く。 */
    public record Enrollment(String secret, String otpauthUri) {
    }
}
