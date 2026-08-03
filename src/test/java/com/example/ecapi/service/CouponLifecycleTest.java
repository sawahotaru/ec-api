package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.CouponRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * クーポンの寿命（検証 → 引き換え → 返却）と、注文に残るスナップショット。
 *
 * <p>在庫の引当と同じ構図の問題がここにもある。「使える」と判断してから「使った」と記録する
 * までに隙間があり、その隙間で別の注文が最後の1枚を取れてしまう。したがって固定すべきは
 * 表示ではなく<strong>数え方</strong>:
 *
 * <ul>
 *   <li>上限を超えて引き換えられないこと（超えた側は 409 で、400 ではない）</li>
 *   <li>売上にならなかった注文（キャンセル・期限切れ）の分は<strong>戻る</strong>こと</li>
 *   <li>戻しすぎてマイナスにならないこと</li>
 *   <li>注文にはコードと金額が<strong>焼き付く</strong>こと（後からクーポンを消しても変わらない）</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        // 送料あり・5,000円で無料、の店として検証する
        "app.shipping.fee=660",
        "app.shipping.free-threshold=5000"
})
class CouponLifecycleTest {

    @Autowired OrderService orderService;
    @Autowired CouponService couponService;
    @Autowired QuoteService quoteService;
    @Autowired CouponRepository couponRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;

    private Product matcha;   // 1,620円 / 軽減8% / 在庫10

    @BeforeEach
    void reset() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        couponRepository.deleteAll();
        taxRateRepository.deleteAll();

        taxRateRepository.save(taxRate(TaxCategory.STANDARD, "10.00"));
        taxRateRepository.save(taxRate(TaxCategory.REDUCED, "8.00"));

        Category category = new Category();
        category.setName("テスト");
        category.setSlug("test");
        category = categoryRepository.save(category);

        matcha = new Product();
        matcha.setName("宇治抹茶 30g");
        matcha.setPrice(new BigDecimal("1620"));
        matcha.setStock(10);
        matcha.setTaxCategory(TaxCategory.REDUCED);
        matcha.setCategory(category);
        matcha = productRepository.save(matcha);
    }

    /* ---------- 検証 ---------- */

    @Test
    @DisplayName("コードは大文字小文字と前後の空白を無視して照合される")
    void codeLookupIsForgiving() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));

        Coupon found = couponService.validate("  welcome500 ", new BigDecimal("3000"), LocalDate.now());

        assertThat(found).isNotNull();
        assertThat(found.getCode()).isEqualTo("WELCOME500");
    }

    @Test
    @DisplayName("空・null は「クーポン無し」であってエラーではない")
    void blankCodeMeansNoCoupon() {
        assertThat(couponService.validate(null, new BigDecimal("3000"), LocalDate.now())).isNull();
        assertThat(couponService.validate("  ", new BigDecimal("3000"), LocalDate.now())).isNull();
    }

    @Test
    @DisplayName("使えない理由がメッセージで分かる（最低金額・期限・上限・無効）")
    void rejectionsExplainThemselves() {
        save(withMin(coupon("MIN3000", DiscountType.FIXED, "500"), "3000"));
        assertThatThrownBy(() -> couponService.validate("MIN3000", new BigDecimal("2999"), LocalDate.now()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("3000");
        // 境界ちょうどは使える（エンティティに equals は無いのでコードで比べる）
        assertThat(couponService.validate("MIN3000", new BigDecimal("3000"), LocalDate.now()).getCode())
                .isEqualTo("MIN3000");

        Coupon expired = coupon("OLD", DiscountType.FIXED, "500");
        expired.setValidTo(LocalDate.now());   // 終了日は排他 ＝ 今日はもう使えない
        save(expired);
        assertThatThrownBy(() -> couponService.validate("OLD", new BigDecimal("3000"), LocalDate.now()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("有効期限");

        Coupon future = coupon("SOON", DiscountType.FIXED, "500");
        future.setValidFrom(LocalDate.now().plusDays(1));
        save(future);
        assertThatThrownBy(() -> couponService.validate("SOON", new BigDecimal("3000"), LocalDate.now()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("まだ");

        Coupon off = coupon("OFF", DiscountType.FIXED, "500");
        off.setEnabled(false);
        save(off);
        assertThatThrownBy(() -> couponService.validate("OFF", new BigDecimal("3000"), LocalDate.now()))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> couponService.validate("NOPE", new BigDecimal("3000"), LocalDate.now()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("見つかりません");
    }

    /* ---------- 引き換え数 ---------- */

    @Test
    @DisplayName("上限に達したら引き換えられない。溢れた側は 409（要求は正しく、在庫が無いだけ）")
    void redemptionRespectsTheLimit() {
        Coupon limited = coupon("ONLYONE", DiscountType.FIXED, "500");
        limited.setMaxRedemptions(1);
        Coupon saved = save(limited);

        couponService.redeem(saved);
        assertThat(reload("ONLYONE").getRedeemedCount()).isEqualTo(1);

        assertThatThrownBy(() -> couponService.redeem(saved)).isInstanceOf(ConflictException.class);
        assertThat(reload("ONLYONE").getRedeemedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("返却は 0 未満に落ちない（二重返却で無限に配れない）")
    void releaseNeverGoesNegative() {
        Coupon saved = save(coupon("REL", DiscountType.FIXED, "500"));
        couponService.redeem(saved);

        couponService.release("REL");
        couponService.release("REL");   // もう戻すものは無い

        assertThat(reload("REL").getRedeemedCount()).isZero();
    }

    /* ---------- 注文との結びつき ---------- */

    @Test
    @DisplayName("注文にコードと金額が焼き付き、引き換え数が1つ進む")
    void checkoutSnapshotsAndRedeems() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));

        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1), "welcome500");

        assertThat(order.getCouponCode()).isEqualTo("WELCOME500");
        assertThat(order.getDiscountAmount()).isGreaterThan(BigDecimal.ZERO);
        // 1,620 − 500 ＝ 1,120。5,000円に届かないので送料660がかかる。
        assertThat(order.getShippingAmount()).isEqualByComparingTo("600");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("1780");
        assertThat(reload("WELCOME500").getRedeemedCount()).isEqualTo(1);

        assertThat(order.getSubtotalAmount()
                .subtract(order.getDiscountAmount())
                .add(order.getShippingAmount())
                .add(order.getTaxAmount()))
                .isEqualByComparingTo(order.getTotalAmount());
    }

    @Test
    @DisplayName("キャンセルすると引き換え数が戻る（売上にならなかったので）")
    void cancellingReturnsTheRedemption() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));
        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1), "WELCOME500");
        assertThat(reload("WELCOME500").getRedeemedCount()).isEqualTo(1);

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(reload("WELCOME500").getRedeemedCount()).isZero();
    }

    @Test
    @DisplayName("未払いのまま期限切れになっても引き換え数が戻る")
    void expiryReturnsTheRedemption() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));
        orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1), "WELCOME500");

        orderService.expireStalePendingOrders(Instant.now().plusSeconds(60));

        assertThat(reload("WELCOME500").getRedeemedCount()).isZero();
    }

    @Test
    @DisplayName("支払い済みの注文をキャンセルしても引き換え数は戻らない（既に売上）")
    void cancellingPaidOrderKeepsTheRedemption() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));
        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1), "WELCOME500");
        orderService.markPaid(order.getId(), "test", "ref");

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(reload("WELCOME500").getRedeemedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("使えないクーポンでの注文は成立せず、在庫も引き当てられない")
    void invalidCouponAbortsTheWholeCheckout() {
        int before = productRepository.findById(matcha.getId()).orElseThrow().getReserved();

        assertThatThrownBy(() -> orderService.guestCheckout(
                "g@example.com", Map.of(matcha.getId(), 1), "NOSUCHCODE"))
                .isInstanceOf(BadRequestException.class);

        assertThat(orderRepository.count()).isZero();
        assertThat(productRepository.findById(matcha.getId()).orElseThrow().getReserved()).isEqualTo(before);
    }

    /* ---------- 見積もりと請求が一致する ---------- */

    @Test
    @DisplayName("見積もりの金額が、そのまま注文の金額になる")
    void quoteMatchesTheOrder() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));

        var quote = quoteService.quote(Map.of(matcha.getId(), 2), "WELCOME500");
        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 2), "WELCOME500");

        assertThat(order.getTotalAmount()).isEqualByComparingTo(quote.total());
        assertThat(order.getTaxAmount()).isEqualByComparingTo(quote.tax());
        assertThat(order.getShippingAmount()).isEqualByComparingTo(quote.shipping());
        assertThat(order.getDiscountAmount()).isEqualByComparingTo(quote.discount());
    }

    @Test
    @DisplayName("見積もりは在庫にもクーポンの引き換え数にも触らない")
    void quoteChangesNothing() {
        save(coupon("WELCOME500", DiscountType.FIXED, "500"));

        quoteService.quote(Map.of(matcha.getId(), 1), "WELCOME500");

        assertThat(productRepository.findById(matcha.getId()).orElseThrow().getReserved()).isZero();
        assertThat(reload("WELCOME500").getRedeemedCount()).isZero();
        assertThat(orderRepository.count()).isZero();
    }

    /* ---------- helpers ---------- */

    private Coupon save(Coupon coupon) {
        return couponRepository.save(coupon);
    }

    private Coupon reload(String code) {
        return couponRepository.findByCode(code).orElseThrow();
    }

    private static Coupon coupon(String code, DiscountType type, String value) {
        Coupon coupon = new Coupon();
        coupon.setCode(code);
        coupon.setDiscountType(type);
        coupon.setValue(new BigDecimal(value));
        return coupon;
    }

    private static Coupon withMin(Coupon coupon, String min) {
        coupon.setMinSubtotal(new BigDecimal(min));
        return coupon;
    }

    private static TaxRate taxRate(TaxCategory category, String percent) {
        TaxRate rate = new TaxRate();
        rate.setCategory(category);
        rate.setRatePercent(new BigDecimal(percent));
        rate.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        return rate;
    }
}
