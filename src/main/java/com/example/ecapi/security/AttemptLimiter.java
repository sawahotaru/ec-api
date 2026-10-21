package com.example.ecapi.security;

import com.example.ecapi.privacy.ContactMask;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「失敗を数えて、続いたら一定時間締める」だけの部品。
 *
 * <p>もとは {@link LoginThrottle} の中身だった。<strong>総当たりで困る場所はログインだけではない</strong>
 * （クーポンコードも、存在を当てに来られる）。同じ性質のものを2つ書くと、片方だけに
 * 上限とエビクションが入っている状態になりやすいので、engine をここに1つ置いて共有する。
 *
 * <h2>設計（{@link LoginThrottle} から引き継いだ判断）</h2>
 * <ul>
 *   <li><b>ロックの開始は「失敗が続いた時点」。</b> 窓の終わりを待つと、その間は無制限に試せる。</li>
 *   <li><b>状態はメモリ。</b> 1インスタンス構成で十分。DBに書くと「試行のたびに書き込みが走る」＝
 *       攻撃者が書き込み負荷をかけられる。</li>
 *   <li><b>エントリ数に上限。</b> キーは攻撃者が自由に作れる文字列なので、素直な Map は
 *       それ自体がメモリ枯渇の入口になる。</li>
 * </ul>
 *
 * <p>スレッドセーフ。{@code @Component} ではない——用途ごとに別の計数器が要るので、
 * 使う側（{@link LoginThrottle} / {@link CouponAttemptThrottle}）が自分の設定で組み立てる。
 */
public class AttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(AttemptLimiter.class);

    /** 追跡するキー数の上限。超えたら期限切れを掃除し、それでも減らなければ諦めて作り直す。 */
    private static final int MAX_TRACKED = 10_000;

    private final String name;
    private final int maxAttempts;
    private final Duration window;
    private final Duration lockout;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public AttemptLimiter(String name, int maxAttempts, Duration window, Duration lockout) {
        this.name = name;
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.lockout = lockout;
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

    /** 失敗を1回記録する。上限に達したらロックを開始する。 */
    public void registerFailure(String key) {
        String k = normalise(key);
        evictIfCrowded();
        Instant now = Instant.now();
        attempts.compute(k, (ignored, current) -> {
            Attempts a = (current == null || current.isStale(now, window)) ? new Attempts(now) : current;
            int count = a.count.incrementAndGet();
            if (count >= maxAttempts) {
                a.lockedUntil = now.plus(lockout);
                log.warn("{} locked for '{}' after {} failed attempts (until {})",
                        name, maskKey(k), count, a.lockedUntil);
            }
            return a;
        });
    }

    /** 成功したら忘れる。正しい入力をした相手を締め出したままにしない。 */
    public void reset(String key) {
        attempts.remove(normalise(key));
    }

    /** 残り時間を「約N分」で言うための共通の言い回し（0分と言わない）。 */
    public static long minutesUntil(Instant until) {
        return Math.max(1, Duration.between(Instant.now(), until).toMinutes() + 1);
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
            log.warn("{} table is full ({}) — clearing", name, attempts.size());
            attempts.clear();
        }
    }

    private static String normalise(String key) {
        return key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 警告ログに生のキーを残さない。
     *
     * <p>キーはメールアドレスのことも IP のこともある。メールは既存の
     * {@link ContactMask} と同じ伏せ方にして「ログと画面で伏せ方が違う」状態を作らない。
     * それ以外（IP など）は前後だけ残す——同じ相手の再犯かどうかは付き合わせられるが、
     * ログだけを見た人が接続元を名指しできるほどには残さない。
     */
    private static String maskKey(String key) {
        if (key.indexOf('@') >= 0) {
            return ContactMask.maskForLog(key);
        }
        if (key.length() <= 4) {
            return "***";
        }
        return key.substring(0, 2) + "***" + key.substring(key.length() - 2);
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
