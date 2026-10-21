package com.example.ecapi.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ログインの総当たり対策。<strong>これまで ec-api には何も無かった</strong>
 * （clinic 側には `rate_limit.php` があるのに、こちらは無制限に試せた）。
 *
 * <p>公開デモに限った話ではない。管理者パスワードは環境変数で与える運用なので、
 * 弱い値を入れられたときに時間を稼ぐ層がまったく無い状態だった。
 *
 * <h2>設計上の割り切り</h2>
 * <ul>
 *   <li><b>メールアドレス単位で数える。</b> IP 単位だと、送信元を替えるだけで抜けられる
 *       （そして {@code X-Forwarded-For} は詐称できる）。守りたいのはアカウントなので、
 *       アカウントを単位にする。</li>
 *   <li><b>代償として、他人のアドレスを狙って締め出す嫌がらせが可能になる。</b>
 *       ロックは時間で自動的に解け、正しいパスワードでの成功が入れば即座に解除されるので、
 *       「入れなくなる時間」は上限つき。総当たりを許すよりこちらを取る。</li>
 * </ul>
 *
 * <p>数える仕組みそのものは {@link AttemptLimiter}（上限つきのメモリ計数器）。
 * ここはログインという用途に名前と設定を与えているだけで、同じ engine を
 * {@link CouponAttemptThrottle} も使う。
 *
 * <p>⚠️ これは<strong>アカウントを守る</strong>層であって、サーバーの資源を守る層ではない。
 * 「アドレスを次々に替えて登録・注文を撃ち込む」類はここでは1件も止まらない。
 * そちらは {@link com.example.ecapi.security.RateLimitInterceptor} が送信元単位で見ている。
 */
@Component
public class LoginThrottle {

    private final AttemptLimiter limiter;

    public LoginThrottle(
            @Value("${app.auth.max-login-attempts:5}") int maxAttempts,
            @Value("${app.auth.login-window-seconds:900}") long windowSeconds,
            @Value("${app.auth.login-lockout-seconds:900}") long lockoutSeconds) {
        this.limiter = new AttemptLimiter("Login throttle", maxAttempts,
                Duration.ofSeconds(windowSeconds), Duration.ofSeconds(lockoutSeconds));
    }

    /** ロック中なら解除予定時刻、そうでなければ null。 */
    public Instant lockedUntil(String key) {
        return limiter.lockedUntil(key);
    }

    /**
     * 失敗を1回記録する。上限に達したらロックを開始する。
     *
     * <p>ロックの開始は「失敗が続いた時点」であって「窓が閉じた時点」ではない。
     * 窓の終わりを待つと、その間は無制限に試せてしまう。
     */
    public void registerFailure(String key) {
        limiter.registerFailure(key);
    }

    /** 成功したら忘れる。正しい資格情報を持つ人を締め出したままにしない。 */
    public void reset(String key) {
        limiter.reset(key);
    }
}
