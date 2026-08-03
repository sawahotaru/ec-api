package com.example.ecapi.pricing;

import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.service.ShippingSettings;
import com.example.ecapi.service.TaxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Works out what an order costs, in stages: <b>商品 → 割引 → 送料 → 税 → 合計</b>.
 *
 * <p>Splitting this out of {@code OrderService} is the point of the change. Totals used
 * to be accumulated inside the loop that also reserved stock, which meant every new money
 * concept had to be threaded through that loop — and made it impossible to quote a price
 * without touching inventory. Now the arithmetic is a pure function of
 * (lines, coupon, settings) and can be called from checkout and from a quote endpoint
 * alike, with no way for the two to drift apart.
 *
 * <h2>How the discount reaches the tax</h2>
 * A discount lowers the taxable base, so it cannot simply be subtracted from the total at
 * the end — the tax would be computed on money the customer never paid. Instead the
 * discount is <strong>allocated across the lines in proportion to their amounts</strong>,
 * and each line's tax is then computed on what is left of it. Two consequences worth
 * knowing:
 *
 * <ul>
 *   <li>Lines have different rates (10% / 8%), so <em>which</em> lines absorb the discount
 *       changes the tax. Proportional allocation is the neutral choice.</li>
 *   <li>Proportional shares do not divide evenly. The remainder goes to the largest line
 *       (largest-remainder), so the allocated shares always sum to <em>exactly</em> the
 *       discount — never one yen off.</li>
 * </ul>
 *
 * <h2>What "amount" means here</h2>
 * Every intermediate figure is in the shop's own convention (税込 in INCLUSIVE mode,
 * 税抜 in EXCLUSIVE) right up until the end, where it is normalised to
 * tax-exclusive + tax for storage. That is deliberate: a "¥500 off" coupon and a
 * "¥600 送料" both mean the number the shopper sees, so the arithmetic should happen in
 * the units the shopper is looking at.
 */
@Component
public class OrderPricer {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TaxService taxService;
    private final ShippingSettings shippingSettings;

    public OrderPricer(TaxService taxService, ShippingSettings shippingSettings) {
        this.taxService = taxService;
        this.shippingSettings = shippingSettings;
    }

    /**
     * @param products   product → quantity, in the order the lines should appear
     * @param coupon     an already-validated coupon, or null
     * @param on         the date whose tax rates apply
     */
    public OrderPricing price(Map<Product, Integer> products, Coupon coupon, LocalDate on) {
        PricingMode mode = taxService.pricingMode();

        // --- 1) 商品: snapshot each line's price and rate, in the shop's own convention.
        List<Product> order = new ArrayList<>(products.keySet());
        List<BigDecimal> listAmounts = new ArrayList<>();
        BigDecimal itemsList = BigDecimal.ZERO;
        for (Product product : order) {
            BigDecimal amount = product.getPrice()
                    .multiply(BigDecimal.valueOf(products.get(product)));
            listAmounts.add(amount);
            itemsList = itemsList.add(amount);
        }

        // --- 2) 割引: how much comes off, then whose line it comes off.
        BigDecimal discountTotal = discountFor(coupon, itemsList);
        List<BigDecimal> shares = allocate(discountTotal, listAmounts);

        // --- 3) 税（商品）: computed per line on what is left after the discount.
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal itemSubtotal = BigDecimal.ZERO;   // 税抜・割引前
        BigDecimal discountNet = BigDecimal.ZERO;    // 税抜換算の割引額
        BigDecimal itemTax = BigDecimal.ZERO;
        for (int i = 0; i < order.size(); i++) {
            Product product = order.get(i);
            TaxCategory category = product.getTaxCategory();
            BigDecimal rate = taxService.rateFor(category, on);
            BigDecimal listAmount = listAmounts.get(i);
            BigDecimal share = shares.get(i);

            // 税は「割引後」に対してかかる。割引前の税額を別途出しておくのは、
            // 「税抜小計」を割引前の値として持つため（total の式で割引が二重に効かない）。
            BigDecimal taxBefore = taxService.taxForLine(listAmount, rate, mode);
            BigDecimal taxAfter = taxService.taxForLine(listAmount.subtract(share), rate, mode);

            BigDecimal netBefore = netOf(listAmount, taxBefore, mode);
            BigDecimal netAfter = netOf(listAmount.subtract(share), taxAfter, mode);

            itemSubtotal = itemSubtotal.add(netBefore);
            discountNet = discountNet.add(netBefore.subtract(netAfter));
            itemTax = itemTax.add(taxAfter);

            lines.add(new PricedLine(product, products.get(product), product.getPrice(),
                    category, rate, listAmount, share, taxAfter));
        }

        // --- 4) 送料: on what the customer actually pays for the items.
        BigDecimal itemsAfterDiscount = itemsList.subtract(discountTotal);
        boolean freeShipping = shippingSettings.isFreeFor(itemsAfterDiscount)
                || (coupon != null && coupon.getDiscountType() == DiscountType.FREE_SHIPPING);
        BigDecimal shippingList = freeShipping ? BigDecimal.ZERO : shippingSettings.fee();
        // 送料は標準税率。軽減税率は飲食料品等に対するもので、運賃には及ばない。
        BigDecimal shippingRate = shippingList.signum() == 0
                ? BigDecimal.ZERO
                : taxService.rateFor(TaxCategory.STANDARD, on);
        BigDecimal shippingTax = taxService.taxForLine(shippingList, shippingRate, mode);
        BigDecimal shippingNet = netOf(shippingList, shippingTax, mode);

        // --- 5) 合計
        BigDecimal tax = itemTax.add(shippingTax);
        BigDecimal total = itemSubtotal.subtract(discountNet).add(shippingNet).add(tax);

        return new OrderPricing(lines, scale(itemSubtotal), scale(discountNet), scale(shippingNet),
                scale(shippingTax), scale(tax), scale(total),
                coupon != null ? coupon.getCode() : null, freeShipping);
    }

    /**
     * How much the coupon takes off the item total, capped at the item total itself
     * (a ¥1000 coupon on a ¥600 cart discounts ¥600, it does not hand back ¥400).
     */
    private BigDecimal discountFor(Coupon coupon, BigDecimal itemsList) {
        if (coupon == null || itemsList.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal raw = switch (coupon.getDiscountType()) {
            case PERCENT -> itemsList.multiply(coupon.getValue())
                    .divide(HUNDRED, 0, RoundingMode.FLOOR);
            case FIXED -> coupon.getValue();
            case FREE_SHIPPING -> BigDecimal.ZERO;
        };
        return raw.min(itemsList).max(BigDecimal.ZERO);
    }

    /**
     * Splits {@code total} across {@code amounts} in proportion, giving the rounding
     * remainder to the largest line so the parts sum to exactly {@code total}.
     *
     * <p>Without the remainder step the shares come up short by a few yen and the order's
     * discount silently stops matching the sum of its lines — the kind of drift that is
     * invisible per order and impossible to reconcile in aggregate.
     */
    private List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> amounts) {
        List<BigDecimal> shares = new ArrayList<>(amounts.size());
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0 || sum.signum() <= 0) {
            amounts.forEach(a -> shares.add(BigDecimal.ZERO));
            return shares;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            BigDecimal share = total.multiply(amount).divide(sum, 0, RoundingMode.FLOOR);
            shares.add(share);
            allocated = allocated.add(share);
        }
        BigDecimal remainder = total.subtract(allocated);
        if (remainder.signum() != 0) {
            int biggest = 0;
            for (int i = 1; i < amounts.size(); i++) {
                if (amounts.get(i).compareTo(amounts.get(biggest)) > 0) {
                    biggest = i;
                }
            }
            shares.set(biggest, shares.get(biggest).add(remainder));
        }
        return shares;
    }

    /** The tax-exclusive part of an amount expressed in the shop's convention. */
    private BigDecimal netOf(BigDecimal amount, BigDecimal tax, PricingMode mode) {
        return mode == PricingMode.INCLUSIVE ? amount.subtract(tax) : amount;
    }

    /**
     * Money leaves here at scale 2. {@code UNNECESSARY} is intentional: every figure above
     * is built from scale-≤2 inputs, so a value needing to be rounded here would mean the
     * arithmetic produced sub-yen money — a bug worth failing on rather than papering over.
     */
    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
