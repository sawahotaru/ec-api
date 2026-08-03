package com.example.ecapi.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.service.SettingService;
import com.example.ecapi.service.ShippingSettings;
import com.example.ecapi.service.TaxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 金額計算の段階（商品 → 割引 → 送料 → 税 → 合計）。
 *
 * <p>Spring を起動せず、税率・送料設定を直接与えて計算だけを回す。値段の正しさは
 * 「動いた」では確かめられない類のもので、<strong>桁が1つずれても画面は正常に見える</strong>。
 * ここで固定しているのは主に次の4点:
 *
 * <ol>
 *   <li><b>恒等式</b> {@code total = 小計 − 割引 + 送料 + 税} が内税・外税の両方で成り立つこと。</li>
 *   <li><b>割引は税に効く</b>こと。割引後の金額に税がかかる（最後に総額から引くのではない）。</li>
 *   <li><b>按分の端数が消えない</b>こと。行ごとの割引の合計が、注文の割引額と1円もずれない。</li>
 *   <li><b>送料は標準税率</b>で、割引後の商品合計でしきい値判定されること。</li>
 * </ol>
 */
class OrderPricerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);

    /* ---------- 内税（本番と同じ既定） ---------- */

    @Nested
    @DisplayName("内税モード")
    class Inclusive {

        @Test
        @DisplayName("割引も送料も無ければ、これまでどおりの金額になる")
        void plainOrder() {
            OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0")
                    .price(cart(product("1620", TaxCategory.REDUCED), 1), null, TODAY);

            // 1620円(税込8%) → 税 120 / 税抜 1500
            assertThat(p.itemSubtotal()).isEqualByComparingTo("1500");
            assertThat(p.tax()).isEqualByComparingTo("120");
            assertThat(p.total()).isEqualByComparingTo("1620");
            assertIdentityHolds(p);
        }

        @Test
        @DisplayName("定額クーポンは税込表示から引かれ、税もその分だけ減る")
        void fixedCouponReducesTax() {
            Map<Product, Integer> cart = cart(product("1620", TaxCategory.REDUCED), 1);

            OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0")
                    .price(cart, fixed("500"), TODAY);

            // 税込 1620 − 500 = 1120 を払う。税は 1120 に対して 8% 分（＝82）
            assertThat(p.total()).isEqualByComparingTo("1120");
            assertThat(p.tax()).isEqualByComparingTo("82");
            // 割引は税抜換算で記録される（小計1500 → 割引後の税抜1038）
            assertThat(p.discount()).isEqualByComparingTo("462");
            assertIdentityHolds(p);
        }

        @Test
        @DisplayName("割引を「最後に総額から引く」実装との違いが出る")
        void discountIsNotAppliedAfterTax() {
            // もし税を割引前に計算していたら tax は 120 のままになる。
            OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0")
                    .price(cart(product("1620", TaxCategory.REDUCED), 1), fixed("500"), TODAY);

            assertThat(p.tax()).isNotEqualByComparingTo("120");
        }

        @Test
        @DisplayName("送料は標準税率10%。軽減税率の商品でも送料の税率は変わらない")
        void shippingIsAlwaysStandardRate() {
            OrderPricing p = pricer(PricingMode.INCLUSIVE, "660", "0")
                    .price(cart(product("1080", TaxCategory.REDUCED), 1), null, TODAY);

            // 送料660(税込10%) → 税 60 / 税抜 600
            assertThat(p.shipping()).isEqualByComparingTo("600");
            assertThat(p.shippingTax()).isEqualByComparingTo("60");
            assertThat(p.total()).isEqualByComparingTo("1740");   // 1080 + 660
            assertIdentityHolds(p);
        }
    }

    /* ---------- 外税 ---------- */

    @Nested
    @DisplayName("外税モード")
    class Exclusive {

        @Test
        @DisplayName("税抜価格に税が加算され、恒等式も成り立つ")
        void plainOrder() {
            OrderPricing p = pricer(PricingMode.EXCLUSIVE, "0", "0")
                    .price(cart(product("1000", TaxCategory.STANDARD), 2), null, TODAY);

            assertThat(p.itemSubtotal()).isEqualByComparingTo("2000");
            assertThat(p.tax()).isEqualByComparingTo("200");
            assertThat(p.total()).isEqualByComparingTo("2200");
            assertIdentityHolds(p);
        }

        @Test
        @DisplayName("定額クーポンは税抜から引かれる（表示している数字と同じ流儀）")
        void fixedCouponIsNet() {
            OrderPricing p = pricer(PricingMode.EXCLUSIVE, "0", "0")
                    .price(cart(product("1000", TaxCategory.STANDARD), 2), fixed("500"), TODAY);

            assertThat(p.discount()).isEqualByComparingTo("500");
            assertThat(p.tax()).isEqualByComparingTo("150");      // 1500 の 10%
            assertThat(p.total()).isEqualByComparingTo("1650");
            assertIdentityHolds(p);
        }
    }

    /* ---------- 按分 ---------- */

    @Test
    @DisplayName("税率の違う行に割引が按分され、行ごとの割引の合計が注文の割引と一致する")
    void discountIsSpreadAcrossLines() {
        Map<Product, Integer> cart = new LinkedHashMap<>();
        cart.put(product("1080", TaxCategory.REDUCED), 1);    // 8%
        cart.put(product("2200", TaxCategory.STANDARD), 1);   // 10%

        OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0").price(cart, fixed("1000"), TODAY);

        BigDecimal allocated = p.lines().stream()
                .map(PricedLine::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocated).isEqualByComparingTo("1000");     // 端数が消えていない
        assertThat(p.total()).isEqualByComparingTo("2280");     // 3280 − 1000
        assertIdentityHolds(p);

        // 按分は金額比。大きい行のほうが多く負担する。
        assertThat(p.lines().get(1).discount()).isGreaterThan(p.lines().get(0).discount());
    }

    @Test
    @DisplayName("割り切れない按分でも1円も失わない（3行・端数の出る割引額）")
    void allocationLosesNothingToRounding() {
        Map<Product, Integer> cart = new LinkedHashMap<>();
        cart.put(product("333", TaxCategory.STANDARD), 1);
        cart.put(product("333", TaxCategory.STANDARD), 1);
        cart.put(product("334", TaxCategory.STANDARD), 1);

        OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0").price(cart, fixed("100"), TODAY);

        BigDecimal allocated = p.lines().stream()
                .map(PricedLine::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocated).isEqualByComparingTo("100");
        assertThat(p.total()).isEqualByComparingTo("900");
        assertIdentityHolds(p);
    }

    /* ---------- クーポンの種類 ---------- */

    @Test
    @DisplayName("率クーポンは商品合計に対してかかる")
    void percentCoupon() {
        Coupon coupon = coupon(DiscountType.PERCENT, "10");
        OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0")
                .price(cart(product("2000", TaxCategory.STANDARD), 1), coupon, TODAY);

        assertThat(p.total()).isEqualByComparingTo("1800");
        assertIdentityHolds(p);
    }

    @Test
    @DisplayName("商品より高い定額クーポンでも、マイナスにはならない（お釣りは出ない）")
    void discountIsCappedAtTheItemTotal() {
        OrderPricing p = pricer(PricingMode.INCLUSIVE, "0", "0")
                .price(cart(product("600", TaxCategory.STANDARD), 1), fixed("1000"), TODAY);

        assertThat(p.total()).isEqualByComparingTo("0");
        assertThat(p.total().signum()).isNotNegative();
        assertIdentityHolds(p);
    }

    @Test
    @DisplayName("送料無料クーポンは送料だけを消し、商品の金額には触らない")
    void freeShippingCouponLeavesItemsAlone() {
        OrderPricing p = pricer(PricingMode.INCLUSIVE, "660", "0")
                .price(cart(product("1080", TaxCategory.REDUCED), 1),
                        coupon(DiscountType.FREE_SHIPPING, "0"), TODAY);

        assertThat(p.freeShipping()).isTrue();
        assertThat(p.shipping()).isEqualByComparingTo("0");
        assertThat(p.discount()).isEqualByComparingTo("0");
        assertThat(p.total()).isEqualByComparingTo("1080");
        assertIdentityHolds(p);
    }

    /* ---------- 送料無料しきい値 ---------- */

    @Test
    @DisplayName("しきい値ちょうどで送料無料になる（以上、であって超過ではない）")
    void thresholdIsInclusive() {
        OrderPricing p = pricer(PricingMode.INCLUSIVE, "660", "5000")
                .price(cart(product("5000", TaxCategory.STANDARD), 1), null, TODAY);

        assertThat(p.freeShipping()).isTrue();
        assertThat(p.total()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("しきい値判定は割引後で行う（クーポンで下回れば送料がかかる）")
    void thresholdUsesTheDiscountedTotal() {
        Map<Product, Integer> cart = cart(product("5000", TaxCategory.STANDARD), 1);

        assertThat(pricer(PricingMode.INCLUSIVE, "660", "5000")
                .price(cart, null, TODAY).freeShipping()).isTrue();

        OrderPricing discounted = pricer(PricingMode.INCLUSIVE, "660", "5000")
                .price(cart, fixed("500"), TODAY);
        assertThat(discounted.freeShipping()).isFalse();
        assertThat(discounted.total()).isEqualByComparingTo("5160");   // 4500 + 660
        assertIdentityHolds(discounted);
    }

    @Test
    @DisplayName("しきい値0は「送料無料にならない」設定として扱う（全注文が無料にはならない）")
    void zeroThresholdMeansNeverFree() {
        OrderPricing p = pricer(PricingMode.INCLUSIVE, "660", "0")
                .price(cart(product("100000", TaxCategory.STANDARD), 1), null, TODAY);

        assertThat(p.freeShipping()).isFalse();
        assertThat(p.shipping()).isGreaterThan(BigDecimal.ZERO);
    }

    /* ---------- helpers ---------- */

    /** 小計 − 割引 + 送料 + 税 = 合計。段階表示が破綻していないことの唯一の判定基準。 */
    private static void assertIdentityHolds(OrderPricing p) {
        BigDecimal rebuilt = p.itemSubtotal()
                .subtract(p.discount())
                .add(p.shipping())
                .add(p.tax());
        assertThat(rebuilt)
                .as("小計 %s − 割引 %s + 送料 %s + 税 %s should equal 合計 %s",
                        p.itemSubtotal(), p.discount(), p.shipping(), p.tax(), p.total())
                .isEqualByComparingTo(p.total());
    }

    private static Map<Product, Integer> cart(Product product, int quantity) {
        Map<Product, Integer> cart = new LinkedHashMap<>();
        cart.put(product, quantity);
        return cart;
    }

    private static Product product(String price, TaxCategory category) {
        Product product = new Product();
        product.setName("商品 " + price);
        product.setPrice(new BigDecimal(price).setScale(2, RoundingMode.UNNECESSARY));
        product.setTaxCategory(category);
        return product;
    }

    private static Coupon fixed(String yen) {
        return coupon(DiscountType.FIXED, yen);
    }

    private static Coupon coupon(DiscountType type, String value) {
        Coupon coupon = new Coupon();
        coupon.setCode("TEST");
        coupon.setDiscountType(type);
        coupon.setValue(new BigDecimal(value));
        return coupon;
    }

    /**
     * 標準10% / 軽減8% を返す TaxService と、固定の送料設定で組んだ計算器。
     *
     * <p>依存はモックで与える。ここで確かめたいのは<strong>四則演算と段階の組み立て</strong>
     * だけなので、DB も Spring も要らない（要るようにしてしまうと、金額のケースを増やすのが
     * 億劫になって網羅が痩せる）。
     */
    private static OrderPricer pricer(PricingMode mode, String fee, String freeThreshold) {
        TaxRateRepository rates = mock(TaxRateRepository.class);
        when(rates.findEffective(any(), any())).thenAnswer(call -> {
            TaxCategory category = call.getArgument(0);
            TaxRate rate = new TaxRate();
            rate.setCategory(category);
            rate.setRatePercent(category == TaxCategory.REDUCED
                    ? new BigDecimal("8.00") : new BigDecimal("10.00"));
            rate.setEffectiveFrom(LocalDate.of(2019, 10, 1));
            return List.of(rate);
        });

        SettingService settings = mock(SettingService.class);
        when(settings.getPricingMode()).thenReturn(mode);

        ShippingSettings shipping = mock(ShippingSettings.class);
        BigDecimal feeValue = new BigDecimal(fee).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal thresholdValue = new BigDecimal(freeThreshold).setScale(2, RoundingMode.UNNECESSARY);
        when(shipping.fee()).thenReturn(feeValue);
        when(shipping.freeThreshold()).thenReturn(thresholdValue);
        // isFreeFor は本物のロジックを使う（しきい値の境界はここで検証したい対象そのもの）。
        when(shipping.isFreeFor(any())).thenAnswer(call -> {
            BigDecimal itemsTotal = call.getArgument(0);
            return thresholdValue.signum() > 0 && itemsTotal.compareTo(thresholdValue) >= 0;
        });

        return new OrderPricer(new TaxService(rates, settings, RoundingMode.FLOOR), shipping);
    }
}
