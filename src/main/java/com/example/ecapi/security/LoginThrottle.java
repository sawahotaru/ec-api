package com.example.ecapi.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li><b>状態はメモリに置く。</b> 1インスタンス構成なので十分で、DBに書くと
 *       「ログイン試行のたびに書き込みが走る」＝攻撃者が書き込み負荷をかけられる。
 *       再起動で消えるのは弱点だが、再起動を誘発できるなら他に困る問題がある。</li>
 *   <li><b>エントリ数に上限を設ける。</b> キーは攻撃者が自由に作れる文字列なので、
 *       素直な Map はそれ自体がメモリ枯渇の入口になる。</li>
 * </ul>
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    /** 追跡するアカウント数の上限。超えたら期限切れを掃除し、それでも減らなければ古い順に捨てる。 */
    private static final int MAX_TRACKED = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Duration lockout;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginThrottle(
            @Value("${app.auth.max-login-attempts:5}") int maxAttempts,
            @Value("${app.auth.login-window-seconds:900}") long windowSeconds,
            @Value("${app.auth.login-lockout-seconds:900}") long lockoutSeconds) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
        this.lockout = Duration.ofSeconds(lockoutSeconds);
    }

    /** ロック中なら解除予定時刻、そうでなければ null。 */
    public Instant lockedUntil(String key) {
        Attempts current = attempts.get(normalise(key));
        if (current == null) {
            return null;
        }
        Instant until = current.lockedUntil;
        return until != null && until.isAfter(Instant.now()) ? until : null;
    }

    /**
     * 失敗を1回記録する。上限に達したらロックを開始する。
     *
     * <p>ロックの開始は「失敗が続いた時点」であって「窓が閉じた時点」ではない。
     * 窓の終わりを待つと、その間は無制限に試せてしまう。
     */
    public void registerFailure(String key) {
        String k = normalise(key);
        evictIfCrowded();
        Instant now = Instant.now();
        attempts.compute(k, (ignored, current) -> {
            Attempts a = (current == null || current.isStale(now, window)) ? new Attempts(now) : current;
            int count = a.count.incrementAndGet();
            if (count >= maxAttempts) {
                a.lockedUntil = now.plus(lockout);
                log.warn("Login locked for '{}' after {} failed attempts (until {})",
                        maskKey(k), count, a.lockedUntil);
            }
            return a;
        });
    }

    /** 成功したら忘れる。正しい資格情報を持つ人を締め出したままにしない。 */
    public void reset(String key) {
        attempts.remove(normalise(key));
    }

    private void evictIfCrowded() {
        if (attempts.size() < MAX_TRACKED) {
            return;
        }
        Instant now = Instant.now();
        attempts.entrySet().removeIf(e -> e.getValue().isStale(now, window) && e.getValue().lockedUntil == null);
        if (attempts.size() >= MAX_TRACKED) {
            // それでも埋まっているなら、追跡を諦めて作り直す。ロックが一部消えるが、
            // 「メモリを食い潰して落ちる」よりはよい（そもそもこの状態自体が攻撃の兆候）。
            log.warn("Login throttle table is full ({}) — clearing", attempts.size());
            attempts.clear();
        }
    }

    private static String normalise(String key) {
        return key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 警告ログにメールアドレスを平文で残さない。 */
    private static String maskKey(String key) {
        return com.example.ecapi.privacy.ContactMask.maskForLog(key);
    }

    private static final class Attempts {
        private final AtomicInteger count = new AtomicInteger();
        private final Instant first;
        private volatile Instant lockedUntil;

        private Attempts(Instant first) {
            this.first = first;
        }

        private boolean isStale(Instant now, Duration window) {
            return lockedUntil == null && first.plus(window).isBefore(now);
        }
    }
}
