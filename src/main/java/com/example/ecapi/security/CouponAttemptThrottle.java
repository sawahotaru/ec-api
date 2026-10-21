package com.example.ecapi.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * クーポンコードの当てずっぽうを止める。
 *
 * <h2>なぜ要るのか</h2>
 * {@code POST /api/checkout/quote} は未認証で叩けて、使えない理由を
 * <strong>種類別に</strong>返す（見つかりません／期限切れ／上限到達／最低金額◯円以上）。
 * 買い手に理由を伝えるのは正しい設計で、これは変えない——「使えません」とだけ言われた客は
 * 1品足せば使えたことに気付かず、カートを捨てる。
 *
 * <p>ただし<strong>無制限だと総当たりの計数器になる</strong>。「見つかりません」と
 * それ以外の区別がつくので、応答を数千回引くだけで存在するコードが列挙できる。
 * 理由を隠すのではなく<strong>回数を止める</strong>——そうすれば親切さを失わずに済む。
 *
 * <h2>数え方</h2>
 * 数えるのは<strong>拒否された入力だけ</strong>で、送信元ごと（{@link ClientKey}）。
 * 正しいコードを入れたら忘れる。画面はカートを触るたびに見積もりを取り直すので、
 * 「正しいコードを適用したまま買い物を続ける」人が窓を使い切ることはない。
 *
 * <p>手で打ち間違える人の分は残す（既定10回／15分）。コードは数文字〜十数文字なので、
 * この回数で当てられる空間ではない。
 */
@Component
public class CouponAttemptThrottle {

    private final AttemptLimiter limiter;

    public CouponAttemptThrottle(
            @Value("${app.coupon.max-attempts:10}") int maxAttempts,
            @Value("${app.coupon.window-seconds:900}") long windowSeconds,
            @Value("${app.coupon.lockout-seconds:900}") long lockoutSeconds) {
        this.limiter = new AttemptLimiter("Coupon throttle", maxAttempts,
                Duration.ofSeconds(windowSeconds), Duration.ofSeconds(lockoutSeconds));
    }

    public Instant lockedUntil(String key) {
        return limiter.lockedUntil(key);
    }

    public void registerFailure(String key) {
        limiter.registerFailure(key);
    }

    public void reset(String key) {
        limiter.reset(key);
    }
}
