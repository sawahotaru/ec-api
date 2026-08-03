package com.example.ecapi.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.AuthDtos.LoginRequest;
import com.example.ecapi.dto.AuthDtos.LoginResponse;
import com.example.ecapi.dto.AuthDtos.MfaLoginRequest;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.TooManyAttemptsException;
import com.example.ecapi.repository.UserRepository;
import com.example.ecapi.security.JwtService;
import com.example.ecapi.security.LoginThrottle;
import com.example.ecapi.service.AuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 二段階認証のライフサイクル（登録 → ログイン → 解除）。
 *
 * <h2>この機能で唯一致命的な間違い</h2>
 * ステートレスな JWT で二段階認証を作るとき、<strong>パスワード認証の時点で
 * アクセストークンを返してしまう</strong>のが定番の失敗。二段階目を飛ばしても API が
 * 叩けるので、認証アプリの登録は<strong>ただの飾り</strong>になる。しかも画面は
 * 正しく動いて見えるため、テスト以外に気付く手段が無い。
 *
 * <p>そこで中心に据えるのは次の2点:
 * <ol>
 *   <li>MFA が有効なら、ログインは<b>アクセストークンを返さない</b></li>
 *   <li>渡される MFA トークンでは<b>通常のAPIが叩けない</b></li>
 * </ol>
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.auth.max-login-attempts=3"
})
class MfaLoginTest {

    private static final String EMAIL = "admin@test.local";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired AuthService authService;
    @Autowired MfaService mfaService;
    @Autowired JwtService jwtService;
    @Autowired LoginThrottle throttle;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private User user;

    @BeforeEach
    void reset() {
        throttle.reset(EMAIL);
        userRepository.deleteAll();

        User u = new User();
        u.setEmail(EMAIL);
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setName("Admin");
        u.setRole(Role.ADMIN);
        user = userRepository.save(u);
    }

    /* ---------- 登録 ---------- */

    @Test
    @DisplayName("鍵を発行しただけでは有効にならない（登録に失敗した人を締め出さない）")
    void enrollmentIsNotEnabledUntilConfirmed() {
        mfaService.startEnrollment(user);
        User stored = userRepository.findByEmail(EMAIL).orElseThrow();

        assertThat(stored.getMfaSecret()).isNotBlank();
        assertThat(stored.isMfaEnabled()).isFalse();

        // この時点のログインは一段階のまま
        assertThat(authService.login(new LoginRequest(EMAIL, PASSWORD)).mfaRequired()).isFalse();
    }

    @Test
    @DisplayName("コードを1回通して初めて有効になり、リカバリコードがその時だけ返る")
    void confirmingEnablesAndReturnsRecoveryCodes() {
        String secret = mfaService.startEnrollment(user).secret();

        List<String> codes = mfaService.confirmEnrollment(reload(), currentCode(secret));

        assertThat(codes).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("[A-Z2-9]{4}-[A-Z2-9]{4}"));
        assertThat(reload().isMfaEnabled()).isTrue();
        // 保存はハッシュ。平文はどこにも残らない
        assertThat(reload().getMfaRecoveryCodes()).doesNotContain(codes.get(0));
    }

    @Test
    @DisplayName("間違ったコードでは有効化されない")
    void wrongCodeDoesNotEnable() {
        mfaService.startEnrollment(user);

        assertThatThrownBy(() -> mfaService.confirmEnrollment(reload(), "000000"))
                .isInstanceOf(BadRequestException.class);
        assertThat(reload().isMfaEnabled()).isFalse();
    }

    /* ---------- ログイン（本丸） ---------- */

    @Test
    @DisplayName("🔴 MFA有効ならログインはアクセストークンを返さない")
    void loginWithMfaReturnsNoAccessToken() {
        enableMfa();

        LoginResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.mfaRequired()).isTrue();
        assertThat(response.token()).as("アクセストークン").isNull();
        assertThat(response.mfaToken()).isNotBlank();
        // 認証はまだ完了していない。パスワードだけ持っている相手に氏名や権限を教えない
        assertThat(response.user()).as("ユーザー情報").isNull();
    }

    @Test
    @DisplayName("🔴 MFAトークンでは通常APIが叩けない（用途が焼き込んである）")
    void mfaTokenIsNotAnAccessToken() {
        enableMfa();
        String mfaToken = authService.login(new LoginRequest(EMAIL, PASSWORD)).mfaToken();

        // JwtAuthenticationFilter が使う判定。ここが true だと二段階認証は飾りになる
        assertThat(jwtService.isValid(mfaToken)).isFalse();
        assertThat(jwtService.isValidMfaToken(mfaToken)).isTrue();
    }

    @Test
    @DisplayName("逆に、アクセストークンは二段階目の引換券として使えない")
    void accessTokenCannotStandInForTheMfaToken() {
        String access = authService.login(new LoginRequest(EMAIL, PASSWORD)).token();

        assertThat(jwtService.isValidMfaToken(access)).isFalse();
    }

    @Test
    @DisplayName("正しいコードを通すと、そこで初めてアクセストークンが出る")
    void correctCodeIssuesTheAccessToken() {
        String secret = enableMfa();
        String mfaToken = authService.login(new LoginRequest(EMAIL, PASSWORD)).mfaToken();

        var auth = authService.verifyMfa(new MfaLoginRequest(mfaToken, currentCode(secret)));

        assertThat(auth.token()).isNotBlank();
        assertThat(jwtService.isValid(auth.token())).isTrue();
        assertThat(auth.user().email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("MFAトークンが無ければ二段階目は通らない（コードだけでは入れない）")
    void mfaStepRequiresTheToken() {
        enableMfa();

        assertThatThrownBy(() -> authService.verifyMfa(new MfaLoginRequest("not-a-token", "123456")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("パスワードが違えば MFAトークンも出ない")
    void wrongPasswordNeverReachesTheSecondStep() {
        enableMfa();

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    /* ---------- 試行制限 ---------- */

    @Test
    @DisplayName("6桁の総当たりも試行制限に掛かる（パスワードが合っていても無制限に試せない）")
    void secondStepIsThrottledToo() {
        enableMfa();
        String mfaToken = authService.login(new LoginRequest(EMAIL, PASSWORD)).mfaToken();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.verifyMfa(new MfaLoginRequest(mfaToken, "000000")))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> authService.verifyMfa(new MfaLoginRequest(mfaToken, "000000")))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /* ---------- リカバリコード ---------- */

    @Test
    @DisplayName("リカバリコードでログインでき、使ったものは消える（使い捨て）")
    void recoveryCodeWorksOnceOnly() {
        String secret = mfaService.startEnrollment(user).secret();
        List<String> codes = mfaService.confirmEnrollment(reload(), currentCode(secret));
        String code = codes.get(0);

        String mfaToken = authService.login(new LoginRequest(EMAIL, PASSWORD)).mfaToken();
        assertThat(authService.verifyMfa(new MfaLoginRequest(mfaToken, code)).token()).isNotBlank();
        assertThat(mfaService.remainingRecoveryCodes(reload())).isEqualTo(9);

        // 同じコードは二度と使えない
        throttle.reset(EMAIL);
        String second = authService.login(new LoginRequest(EMAIL, PASSWORD)).mfaToken();
        assertThatThrownBy(() -> authService.verifyMfa(new MfaLoginRequest(second, code)))
                .isInstanceOf(BadCredentialsException.class);
    }

    /* ---------- 解除 ---------- */

    @Test
    @DisplayName("解除にもコードが要る（盗まれたセッションで黙って外されない）")
    void disablingRequiresACode() {
        String secret = enableMfa();

        assertThatThrownBy(() -> mfaService.disable(reload(), "000000"))
                .isInstanceOf(BadRequestException.class);
        assertThat(reload().isMfaEnabled()).isTrue();

        mfaService.disable(reload(), currentCode(secret));

        User after = reload();
        assertThat(after.isMfaEnabled()).isFalse();
        assertThat(after.getMfaSecret()).isNull();
        assertThat(after.getMfaRecoveryCodes()).isNull();
        // 解除後は一段階に戻る
        assertThat(authService.login(new LoginRequest(EMAIL, PASSWORD)).token()).isNotBlank();
    }

    /* ---------- helpers ---------- */

    private User reload() {
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    private String enableMfa() {
        String secret = mfaService.startEnrollment(user).secret();
        mfaService.confirmEnrollment(reload(), currentCode(secret));
        return secret;
    }

    private static String currentCode(String secret) {
        return Totp.generate(secret, Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
    }
}
