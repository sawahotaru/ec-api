package com.example.ecapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 1回の注文が握れる量の上限。
 *
 * <h2>なぜ要るのか</h2>
 * 在庫は「引当（hold）」で押さえ、支払いが無ければ30分で解ける。これは売り越しを防ぐ良い仕組みだが、
 * <strong>引き当てるのに支払いが要らない</strong>。公開カタログは {@code available} を正確に返すので、
 * 各商品の available ぴったりを1要求で押さえれば、店全体が「在庫切れ」表示になる。
 * デモは決済が無効で注文は必ず失効するから、攻撃側の費用はゼロ——30分おきに1要求で維持できる。
 *
 * <h2>どう止めるか（2段）</h2>
 * <ol>
 *   <li><b>1注文あたりの上限</b>（{@link #maxQuantityPerItem()} / {@link #maxLinesPerOrder()}）。
 *       「1要求で店を空にする」を止める。実店舗の「お一人様◯点まで」と同じもので、
 *       回数制限と掛け算すると、1送信元が単位時間に握れる量が有限になる。</li>
 *   <li><b>1商品あたりの未払い引当の上限</b>（{@link #maxHoldPercent()}）。
 *       <strong>送信元に依存しない</strong>ので、分散しても効く。これが本命:
 *       「未払いの予約が在庫の半分を超えない」なら、引当だけで売り切れ表示にはできない。</li>
 * </ol>
 *
 * <p>支払いが済んだ分は {@code stock} と {@code reserved} の両方から引かれるので、
 * 実際に売れても割合は動かない。つまり2番目の上限は「売れたから買えない」を作らない。
 *
 * <p>既定は<strong>有効</strong>。これは公開デモに固有の話ではなく（自分の店でも、支払っていない
 * 誰かに棚を空にされてよい理由は無い）、しかも<strong>効いていないことに気付く手段が無い</strong>
 * ——攻撃されるまで画面は完全に正常に見える。自分の店として運用するなら上げてよい。
 * {@code max-hold-percent=100} / 数量上限 {@code 0} で、それぞれ従来どおりの無制限に戻せる。
 */
@Component
public class CheckoutLimits {

    private final int maxQuantityPerItem;
    private final int maxLinesPerOrder;
    private final int maxHoldPercent;

    public CheckoutLimits(
            @Value("${app.order.max-quantity-per-item:10}") int maxQuantityPerItem,
            @Value("${app.order.max-lines-per-order:20}") int maxLinesPerOrder,
            @Value("${app.order.max-hold-percent:50}") int maxHoldPercent) {
        this.maxQuantityPerItem = maxQuantityPerItem;
        this.maxLinesPerOrder = maxLinesPerOrder;
        // 100 を超える値は「無制限」と同じ。負値で引当が不可能になる事故を防ぐため下も止める。
        this.maxHoldPercent = Math.max(1, Math.min(100, maxHoldPercent));
    }

    /** 同一商品を1注文で何個まで。0 以下で無制限。 */
    public int maxQuantityPerItem() {
        return maxQuantityPerItem;
    }

    /** 1注文に何種類まで。0 以下で無制限。 */
    public int maxLinesPerOrder() {
        return maxLinesPerOrder;
    }

    /** 1商品の在庫のうち、未払いの引当が占めてよい割合（%）。100 で無制限。 */
    public int maxHoldPercent() {
        return maxHoldPercent;
    }
}
