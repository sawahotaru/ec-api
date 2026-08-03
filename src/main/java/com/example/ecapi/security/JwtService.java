package com.example.ecapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT の発行と検証。
 *
 * <h2>二種類のトークンがある</h2>
 * <ul>
 *   <li><b>アクセストークン</b>（{@code purpose=access}）— 通常のAPI用。</li>
 *   <li><b>MFAトークン</b>（{@code purpose=mfa}）— パスワードは通ったが<strong>まだ本人と認めていない</strong>
 *       状態で渡す短命の引換券。二段階目の検証にしか使えない。</li>
 * </ul>
 *
 * <h2>用途を claim で分けている理由（ここが要点）</h2>
 * ステートレスな JWT で二段階認証を作るとき、いちばんやりがちな失敗が
 * <strong>パスワード認証の時点でアクセストークンを返してしまう</strong>こと。
 * そうすると二段階目を飛ばしても API が叩けてしまい、認証アプリの登録は
 * <strong>ただの飾り</strong>になる。しかも画面上は正しく動いて見えるので気付けない。
 *
 * <p>セッションを持つアプリ（同じワークスペースの clinic-reservation）なら
 * 「まだ admin フラグを立てない」で済むが、こちらはサーバーが状態を持たない。
 * そこで<strong>トークン自体に用途を焼き込み</strong>、{@link JwtAuthenticationFilter} が
 * access 以外を認証に使わないようにしている。
 *
 * <p>古いトークン（purpose claim が無い）は access とみなす。二段階認証を入れる前に
 * 発行されたトークンが、この変更で一斉に無効になるのを避けるため。
 */
@Service
public class JwtService {

    static final String CLAIM_PURPOSE = "purpose";
    static final String PURPOSE_ACCESS = "access";
    static final String PURPOSE_MFA = "mfa";

    private final SecretKey key;
    private final long expirationMs;
    private final long mfaExpirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            @Value("${app.jwt.mfa-expiration-ms:300000}") long mfaExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.mfaExpirationMs = mfaExpirationMs;
    }

    /** 通常のアクセストークン。二段階認証が有効なら、二段階目を通った後にだけ発行される。 */
    public String generateToken(String email, String role) {
        return build(email, role, PURPOSE_ACCESS, expirationMs);
    }

    /**
     * 二段階目の引換券。<strong>これでは何のAPIも叩けない</strong>。
     *
     * <p>有効期限が短い（既定5分）のは、パスワードだけが漏れた状態で
     * 長く持ち歩けるものを渡さないため。role は載せない——使い道が無いうえ、
     * 認証前の値を持ち回るとどこかで流用される。
     */
    public String generateMfaToken(String email) {
        return build(email, null, PURPOSE_MFA, mfaExpirationMs);
    }

    private String build(String email, String role, String purpose, long ttlMs) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(email)
                .claim(CLAIM_PURPOSE, purpose)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMs));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.signWith(key).compact();
    }

    public String extractEmail(String token) {
        return parse(token).getSubject();
    }

    /**
     * 通常APIで使えるトークンか。
     *
     * <p>期限だけでなく<strong>用途も見る</strong>。MFAトークンをそのまま
     * {@code Authorization: Bearer} に入れて叩かれても、ここで落ちる。
     */
    public boolean isValid(String token) {
        return isValid(token, PURPOSE_ACCESS);
    }

    /** 二段階目の検証で使う。 */
    public boolean isValidMfaToken(String token) {
        return isValid(token, PURPOSE_MFA);
    }

    private boolean isValid(String token, String expectedPurpose) {
        try {
            Claims claims = parse(token);
            if (!claims.getExpiration().after(new Date())) {
                return false;
            }
            Object purpose = claims.get(CLAIM_PURPOSE);
            // purpose が無いのは二段階認証を入れる前に発行されたトークン。access として扱う
            // （導入した瞬間に全員がログアウトになるのを避ける）。mfa は明示されたときだけ。
            String actual = purpose != null ? purpose.toString() : PURPOSE_ACCESS;
            return expectedPurpose.equals(actual);
        } catch (Exception ex) {
            return false;
        }
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public long getMfaExpirationMs() {
        return mfaExpirationMs;
    }
}
