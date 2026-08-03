package com.example.ecapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.AuthDtos.LoginRequest;
import com.example.ecapi.exception.TooManyAttemptsException;
import com.example.ecapi.repository.UserRepository;
import com.example.ecapi.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ログイン試行制限。<strong>これまで ec-api には何も無かった</strong>
 * （同じワークスペースの clinic には `rate_limit.php` があるのに、こちらは無制限に試せた）。
 *
 * <p>公開デモに限った話ではない。管理者パスワードは環境変数で与える運用なので、
 * 弱い値を入れられたときに時間を稼ぐ層がまったく無い状態だった。
 *
 * <p>ここで固定するのは「回数で止まること」だけでなく、
 * <strong>止め方が運用を壊さないこと</strong>——正解を入れれば即座に解け、
 * 拒否の理由が 401 と区別できる（401 を返すと本人が「パスワード違い」と誤解して
 * 試し続け、ロックを自分で延ばす）。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.auth.max-login-attempts=3",
        "app.auth.login-window-seconds=900",
        "app.auth.login-lockout-seconds=900"
})
class LoginThrottleTest {

    @Autowired AuthService authService;
    @Autowired LoginThrottle throttle;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String EMAIL = "victim@example.com";

    @BeforeEach
    void reset() {
        throttle.reset(EMAIL);
        throttle.reset("nobody@example.com");
        userRepository.deleteAll();

        User user = new User();
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode("correct-horse"));
        user.setName("Victim");
        user.setRole(Role.USER);
        userRepository.save(user);
    }

    @Test
    @DisplayName("上限までは 401、超えたら 429 相当の例外に変わる")
    void locksAfterTheLimit() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                    .isInstanceOf(BadCredentialsException.class);
        }
        // 3回目で上限に達し、以降は理由が変わる
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("分後");
    }

    @Test
    @DisplayName("ロック中は正しいパスワードでも通さない（そうでないと総当たりが止まらない）")
    void lockAppliesEvenToTheCorrectPassword() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "correct-horse")))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("上限に達する前に成功すれば、失敗の記録は消える")
    void successResetsTheCounter() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(authService.login(new LoginRequest(EMAIL, "correct-horse")).token()).isNotBlank();
        assertThat(throttle.lockedUntil(EMAIL)).isNull();

        // カウンタが戻っているので、また上限まで試せる（1回で即ロックにならない）
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(throttle.lockedUntil(EMAIL)).isNull();
    }

    @Test
    @DisplayName("存在しないアドレスへの試行も数える（「当たりのアドレス探し」を無制限にしない）")
    void unknownAccountsAreThrottledToo() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "x")))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "x")))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("大文字小文字・前後の空白を変えても回数は共有される（表記替えで回避できない）")
    void keyIsNormalised() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("VICTIM@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("  victim@example.com  ", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(throttle.lockedUntil(EMAIL)).isNotNull();
    }

    @Test
    @DisplayName("巻き添えにしない: 別のアカウントは影響を受けない")
    void otherAccountsAreUnaffected() {
        for (int i = 0; i < 4; i++) {
            try {
                authService.login(new LoginRequest("nobody@example.com", "x"));
            } catch (RuntimeException ignored) {
                // 期待どおり失敗する
            }
        }
        assertThat(throttle.lockedUntil("nobody@example.com")).isNotNull();
        assertThat(throttle.lockedUntil(EMAIL)).isNull();
        assertThat(authService.login(new LoginRequest(EMAIL, "correct-horse")).token()).isNotBlank();
    }
}
