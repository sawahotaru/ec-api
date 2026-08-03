package com.example.ecapi.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.CouponRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.service.OrderService;
import com.example.ecapi.stats.StatsDtos.StoreStats;
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
 * 売上集計。
 *
 * <p>この機能の誤りは<strong>画面が正常に見えたまま数字だけが嘘になる</strong>種類なので、
 * 「何を数えて何を数えないか」を1本ずつ固定する。とくに:
 *
 * <ul>
 *   <li><b>未払い（PENDING）を売上に入れない</b> — 在庫を押さえただけの注文が売上に化けると、
 *       実態より良い数字が出続け、しかも辻褄は合っているので気付けない</li>
 *   <li><b>キャンセル・失効も入れない</b>が、件数としては残す（機会損失が見える）</li>
 *   <li><b>商品別はカタログではなく明細から積む</b> — 値上げしただけで去年の売上が動かないこと</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.shipping.fee=0",
        "app.shipping.free-threshold=0"
})
class StatsServiceTest {

    @Autowired StatsService statsService;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CouponRepository couponRepository;
    @Autowired TaxRateRepository taxRateRepository;

    private Product matcha;   // 1,620円 / 軽減8%
    private Product yunomi;   // 2,750円 / 標準10%

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

        matcha = product("宇治抹茶 30g", "1620", TaxCategory.REDUCED, category);
        yunomi = product("藍染湯呑み 二客組", "2750", TaxCategory.STANDARD, category);
    }

    @Test
    @DisplayName("注文が無ければ全部 0（0除算で落ちない）")
    void emptyStoreIsAllZero() {
        StoreStats stats = statsService.collect(12);

        assertThat(stats.revenue()).isEqualByComparingTo("0");
        assertThat(stats.paidOrders()).isZero();
        assertThat(stats.averageOrder()).isEqualByComparingTo("0");
        assertThat(stats.conversionRate()).isEqualByComparingTo("0");
        assertThat(stats.products()).isEmpty();
        assertThat(stats.monthly()).hasSize(12);
        assertThat(stats.monthly()).allSatisfy(p -> assertThat(p.revenue()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("未払いの注文は売上に入らない（件数だけ数える）")
    void pendingIsNotRevenue() {
        orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));

        StoreStats stats = statsService.collect(12);

        assertThat(stats.revenue()).isEqualByComparingTo("0");
        assertThat(stats.paidOrders()).isZero();
        assertThat(stats.pendingOrders()).isEqualTo(1);
        assertThat(stats.products()).isEmpty();     // 明細も売上側には出ない
    }

    @Test
    @DisplayName("支払い済みになった時点で売上に入る")
    void paidBecomesRevenue() {
        Order order = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        orderService.markPaid(order.getId(), "stripe", "ref-1");

        StoreStats stats = statsService.collect(12);

        assertThat(stats.revenue()).isEqualByComparingTo("1620");
        assertThat(stats.paidOrders()).isEqualTo(1);
        assertThat(stats.averageOrder()).isEqualByComparingTo("1620");
        assertThat(stats.pendingOrders()).isZero();
    }

    @Test
    @DisplayName("発送済み・配達済みも売上のまま（PAID の先で消えない）")
    void shippedAndDeliveredStayRevenue() {
        Order first = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        orderService.markPaid(first.getId(), "stripe", "r1");
        orderService.updateStatus(first.getId(), OrderStatus.SHIPPED);

        Order second = orderService.guestCheckout("b@example.com", Map.of(yunomi.getId(), 1));
        orderService.markPaid(second.getId(), "stripe", "r2");
        orderService.updateStatus(second.getId(), OrderStatus.DELIVERED);

        StoreStats stats = statsService.collect(12);

        assertThat(stats.paidOrders()).isEqualTo(2);
        assertThat(stats.revenue()).isEqualByComparingTo("4370");   // 1620 + 2750
    }

    @Test
    @DisplayName("キャンセル・失効は売上ゼロだが、機会損失として件数に残る")
    void cancelledAndExpiredAreCountedButNotEarned() {
        Order cancelled = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        orderService.updateStatus(cancelled.getId(), OrderStatus.CANCELLED);
        orderService.guestCheckout("b@example.com", Map.of(matcha.getId(), 1));
        orderService.expireStalePendingOrders(Instant.now().plusSeconds(60));

        StoreStats stats = statsService.collect(12);

        assertThat(stats.revenue()).isEqualByComparingTo("0");
        assertThat(stats.lostOrders()).isEqualTo(2);
        assertThat(stats.conversionRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("転換率は「作られた注文のうち売上になった割合」")
    void conversionCountsEveryOrderEverCreated() {
        Order paid = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        orderService.markPaid(paid.getId(), "stripe", "r1");
        Order lost = orderService.guestCheckout("b@example.com", Map.of(matcha.getId(), 1));
        orderService.updateStatus(lost.getId(), OrderStatus.CANCELLED);
        orderService.guestCheckout("c@example.com", Map.of(matcha.getId(), 1));   // PENDING のまま

        StoreStats stats = statsService.collect(12);

        // 3件中1件が売上 → 33.3%（PENDING を母数から外すと 50% になってしまう）
        assertThat(stats.conversionRate()).isEqualByComparingTo("33.3");
    }

    @Test
    @DisplayName("商品別は明細のスナップショットから積む（後で値上げしても過去の売上は動かない）")
    void productSalesUseTheSnapshot() {
        Order order = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 2));
        orderService.markPaid(order.getId(), "stripe", "r1");

        BigDecimal before = statsService.collect(12).products().get(0).revenue();

        // カタログ価格を倍にする
        matcha.setPrice(new BigDecimal("3240"));
        productRepository.save(matcha);

        StoreStats after = statsService.collect(12);
        assertThat(after.products()).hasSize(1);
        assertThat(after.products().get(0).productName()).isEqualTo("宇治抹茶 30g");
        assertThat(after.products().get(0).quantity()).isEqualTo(2);
        assertThat(after.products().get(0).revenue()).isEqualByComparingTo(before);
        assertThat(after.products().get(0).revenue()).isEqualByComparingTo("3240");   // 1620 × 2
    }

    @Test
    @DisplayName("商品名が同じでも、削除された商品の売上は残る")
    void productSalesSurviveProductDeletion() {
        Order order = orderService.guestCheckout("a@example.com", Map.of(yunomi.getId(), 1));
        orderService.markPaid(order.getId(), "stripe", "r1");

        StoreStats stats = statsService.collect(12);

        assertThat(stats.products()).hasSize(1);
        assertThat(stats.products().get(0).productName()).isEqualTo("藍染湯呑み 二客組");
    }

    @Test
    @DisplayName("決済手段別に分かれ、手動確定は manual に寄る")
    void providerBreakdown() {
        Order viaStripe = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        orderService.markPaid(viaStripe.getId(), "stripe", "r1");

        Order byHand = orderService.guestCheckout("b@example.com", Map.of(yunomi.getId(), 1));
        orderService.updateStatus(byHand.getId(), OrderStatus.PAID);   // 管理画面からの手動確定

        StoreStats stats = statsService.collect(12);

        assertThat(stats.providers()).hasSize(2);
        assertThat(stats.providers()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo("manual");
            assertThat(p.revenue()).isEqualByComparingTo("2750");
        });
        assertThat(stats.providers()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo("stripe");
            assertThat(p.revenue()).isEqualByComparingTo("1620");
        });
    }

    @Test
    @DisplayName("月別は指定した月数ぶん、注文の無い月も 0 で並ぶ")
    void monthlyIsPaddedWithZeroes() {
        Order order = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        orderService.markPaid(order.getId(), "stripe", "r1");

        StoreStats stats = statsService.collect(6);

        assertThat(stats.monthly()).hasSize(6);
        // 今月（末尾）に売上が立ち、それ以前は 0
        assertThat(stats.monthly().get(5).revenue()).isEqualByComparingTo("1620");
        assertThat(stats.monthly().subList(0, 5))
                .allSatisfy(p -> assertThat(p.revenue()).isEqualByComparingTo("0"));
    }

    /* ---------- helpers ---------- */

    private Product product(String name, String price, TaxCategory category, Category cat) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(50);
        product.setTaxCategory(category);
        product.setCategory(cat);
        return productRepository.save(product);
    }

    private static TaxRate taxRate(TaxCategory category, String percent) {
        TaxRate rate = new TaxRate();
        rate.setCategory(category);
        rate.setRatePercent(new BigDecimal(percent));
        rate.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        return rate;
    }
}
