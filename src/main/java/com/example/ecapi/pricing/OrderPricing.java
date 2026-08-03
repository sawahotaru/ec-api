package com.example.ecapi.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of pricing an order, as the stages that produced it.
 *
 * <p>All money here is <strong>normalised to tax-exclusive amounts plus a separate tax
 * figure</strong>, whichever pricing mode the shop displays. That is what makes the
 * identity below hold in both modes, and it is why the same four numbers can be stored
 * on the order without a flag saying how to read them:
 *
 * <pre>total = itemSubtotal − discount + shipping + tax</pre>
 *
 * <p>({@code itemSubtotal} is <em>before</em> the discount, so the discount appears
 * exactly once. Reporting "小計 / 割引 / 送料 / 税 / 合計" needs it that way — a subtotal
 * that already had the discount folded in cannot be shown next to a discount line.)
 *
 * @param lines        per-line detail, already carrying each line's share of the discount
 * @param itemSubtotal 税抜の商品小計（割引前）
 * @param discount     割引額（税抜換算）
 * @param shipping     送料（税抜）
 * @param shippingTax  送料にかかる消費税（{@code tax} にも含まれる。内訳表示用）
 * @param tax          消費税合計（商品＋送料・割引反映後）
 * @param total        支払総額
 * @param couponCode   適用されたクーポンコード（無ければ null）
 * @param freeShipping 送料が無料になったか（しきい値到達またはクーポン）
 */
public record OrderPricing(
        List<PricedLine> lines,
        BigDecimal itemSubtotal,
        BigDecimal discount,
        BigDecimal shipping,
        BigDecimal shippingTax,
        BigDecimal tax,
        BigDecimal total,
        String couponCode,
        boolean freeShipping) {
}
