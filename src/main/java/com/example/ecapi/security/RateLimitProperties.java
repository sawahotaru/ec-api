package com.example.ecapi.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 送信元ごとの要求回数の上限。
 *
 * <p><strong>既定で有効</strong>。{@code app.demo.*} の3つ（読み取り専用・マスク・保持期間）が
 * 既定で無効なのとは逆にしてある。あちらは「配布物として持ち出した人の環境で、説明も無く
 * 管理機能が使えないのは機能欠陥に見える」から既定で無効なのに対し、これは<strong>どの環境でも
 * 間違いなく必要</strong>で、しかも<strong>有効でないことに気付く手段が無い</strong>
 * （攻撃されるまで画面は完全に正常に見える）。公開デモ側の環境変数で有効にする設計だと、
 * 環境変数を1つ書き忘れた日に店が売り切れる。
 *
 * <p>値は「人間のデモ訪問者が絶対に届かない」ところに置いてある。自分の店として運用するなら
 * 上げてよいし、{@code 0} でその枠だけ無制限にできる（{@code app.rate-limit.enabled=false}
 * で全部切れる）。
 */
@Component
public class RateLimitProperties {

    private final boolean enabled;
    private final Duration window;
    private final Duration registerWindow;
    private final int checkout;
    private final int register;
    private final int quote;
    private final int fallback;

    public RateLimitProperties(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.window-seconds:600}") long windowSeconds,
            @Value("${app.rate-limit.register-window-seconds:3600}") long registerWindowSeconds,
            @Value("${app.rate-limit.checkout:10}") int checkout,
            @Value("${app.rate-limit.register:5}") int register,
            @Value("${app.rate-limit.quote:120}") int quote,
            @Value("${app.rate-limit.default:60}") int fallback) {
        this.enabled = enabled;
        this.window = Duration.ofSeconds(windowSeconds);
        this.registerWindow = Duration.ofSeconds(registerWindowSeconds);
        this.checkout = checkout;
        this.register = register;
        this.quote = quote;
        this.fallback = fallback;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** ほとんどの枠が使う窓（既定10分）。 */
    public Duration window() {
        return window;
    }

    /** 登録だけ窓が長い（既定1時間）。アカウントは「たくさん作られる」こと自体が困るため。 */
    public Duration registerWindow() {
        return registerWindow;
    }

    /** 注文の作成。在庫を握り、行を増やす——いちばん高くつく要求。 */
    public int checkout() {
        return checkout;
    }

    /** 会員登録。`users` が無制限に増える唯一の口。 */
    public int register() {
        return register;
    }

    /** 見積もり。カートを触るたびに画面が呼ぶので、他より桁ひとつ多く要る。 */
    public int quote() {
        return quote;
    }

    /** 上の枠に当てはまらない書き込み全部。 */
    public int fallback() {
        return fallback;
    }
}
