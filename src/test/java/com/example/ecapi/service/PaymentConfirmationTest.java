package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.AppSetting;
import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.event.OrderEvent;
import com.example.ecapi.event.OrderEventListener;
import com.example.ecapi.event.OrderPaidEvent;
import com.example.ecapi.payment.MinorUnits;
import com.example.ecapi.payment.PaymentCallback;
import com.example.ecapi.repository.AppSettingRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Webhook の通知を注文と照合してから PAID にすること（金額・通貨・入金確定）。
 *
 * <p>共有の H2 を汚さないよう、税の方式・送料・基準額は控えて戻し、作った注文・商品・
 * カテゴリ・税率は自分で消す。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000"
})
@Import(PaymentConfirmationTest.PaidRecorderConfig.class)
class PaymentConfirmationTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired SettingService settingService;
    @Autowired ShippingSettings shippingSettings;
    @Autowired AppSettingRepository appSettingRepository;
    @Autowired PaidRecorder recorder;

    /** 設定の行ごとの元の値（元から無かった行は null）。後片付けで行単位に戻す。 */
    private static final List<String> SETTING_KEYS =
            List.of("tax.pricing-mode", "shipping.fee", "shipping.free-threshold");
    private final Map<String, String> savedRows = new HashMap<>();

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> taxRateIds = new ArrayList<>();
    private Category category;

    @BeforeEach
    void setUp() {
        savedRows.clear();
        for (String key : SETTING_KEYS) {
            savedRows.put(key, appSettingRepository.findById(key).map(AppSetting::getValue).orElse(null));
        }
        settingService.setPricingMode(PricingMode.INCLUSIVE);
        shippingSettings.setFee(BigDecimal.ZERO);
        shippingSettings.setFreeThreshold(BigDecimal.ZERO);
        recorder.clear();

        taxRateIds.add(taxRateRepository.save(taxRate(TaxCategory.STANDARD, "10.00")).getId());

        Category c = new Category();
        c.setName("ec02");
        c.setSlug("ec02-" + UUID.randomUUID());
        category = categoryRepository.save(c);
    }

    @AfterEach
    void restore() {
        orderIds.forEach(orderRepository::deleteById);
        productIds.forEach(productRepository::deleteById);
        categoryRepository.deleteById(category.getId());
        taxRateIds.forEach(taxRateRepository::deleteById);

        // 元から無かった行を、このコンテキストの既定値で作ってしまわないよう、行ごとに戻す
        savedRows.forEach((key, value) -> {
            if (value != null) {
                appSettingRepository.save(new AppSetting(key, value));
            } else if (appSettingRepository.existsById(key)) {
                appSettingRepository.deleteById(key);
            }
        });
    }

    @Test
    @DisplayName("1 一致する通知は PAID にして在庫を確定する")
    void matchingNotificationMarksPaid() {
        Product p = product("5990", 10);
        Order order = place(p, 1);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("5990");

        orderService.confirmPayment(callback(order, 5990L, "jpy", true), "stripe", "jpy");

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
        assertThat(reload(p).getStock()).isEqualTo(9);
        assertThat(reload(p).getReserved()).isZero();
        assertThat(recorder.paid()).hasSize(1);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentReference())
                .isEqualTo("cs_test_1");
    }

    @Test
    @DisplayName("2 金額が 1 円違えば PAID にしない（引当も変わらない）")
    void amountOffByOneIsRejected() {
        Product p = product("5990", 10);
        Order order = place(p, 1);

        orderService.confirmPayment(callback(order, 5991L, "jpy", true), "stripe", "jpy");

        assertUntouched(order, p);
    }

    @Test
    @DisplayName("3 通貨が違えば PAID にしない")
    void wrongCurrencyIsRejected() {
        Product p = product("5990", 10);
        Order order = place(p, 1);

        orderService.confirmPayment(callback(order, 5990L, "usd", true), "stripe", "jpy");

        assertUntouched(order, p);
    }

    @Test
    @DisplayName("4 入金が確定していない通知は PAID にしない")
    void notPaidIsRejected() {
        Product p = product("5990", 10);
        Order order = place(p, 1);

        orderService.confirmPayment(callback(order, 5990L, "jpy", false), "stripe", "jpy");

        assertUntouched(order, p);
    }

    @Test
    @DisplayName("5 金額が無い通知は PAID にしない（フェイルクローズ）")
    void missingAmountIsRejected() {
        Product p = product("5990", 10);
        Order order = place(p, 1);

        orderService.confirmPayment(callback(order, null, "jpy", true), "stripe", "jpy");

        assertUntouched(order, p);
    }

    @Test
    @DisplayName("6 EXPIRED の注文に金額の食い違いが届いても EXPIRED のまま")
    void mismatchOnExpiredOrderStaysExpired() {
        Product p = product("5990", 10);
        Order order = place(p, 1);
        Order stored = orderRepository.findById(order.getId()).orElseThrow();
        stored.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(stored);

        orderService.confirmPayment(callback(order, 1L, "jpy", true), "stripe", "jpy");

        assertThat(status(order)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(recorder.paid()).isEmpty();
    }

    @Test
    @DisplayName("7 小数の価格（合計 1000.50）は補助単位 1001 円で一致する")
    void fractionalTotalMatchesInMinorUnits() {
        Product p = product("1000.50", 10);
        Order order = place(p, 1);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("1000.50");
        assertThat(MinorUnits.of(order.getTotalAmount(), "jpy")).isEqualTo(1001L);

        orderService.confirmPayment(callback(order, 1001L, "jpy", true), "stripe", "jpy");

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
    }

    // --- helpers -------------------------------------------------------------

    private void assertUntouched(Order order, Product p) {
        assertThat(status(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(reload(p).getReserved()).isEqualTo(1);
        assertThat(reload(p).getStock()).isEqualTo(10);
        assertThat(recorder.paid()).isEmpty();
    }

    private PaymentCallback callback(Order order, Long amount, String currency, boolean paid) {
        return new PaymentCallback(order.getId(), "cs_test_1", amount, currency, paid, "evt_test_1");
    }

    private Order place(Product p, int qty) {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(p.getId(), qty));
        orderIds.add(order.getId());
        return order;
    }

    private OrderStatus status(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private Product reload(Product p) {
        return productRepository.findById(p.getId()).orElseThrow();
    }

    private Product product(String price, int stock) {
        Product p = new Product();
        p.setName("テスト商品");
        p.setDescription("テスト商品");
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setTaxCategory(TaxCategory.STANDARD);
        p.setCategory(category);
        Product saved = productRepository.save(p);
        productIds.add(saved.getId());
        return saved;
    }

    private TaxRate taxRate(TaxCategory taxCategory, String percent) {
        TaxRate r = new TaxRate();
        r.setCategory(taxCategory);
        r.setRatePercent(new BigDecimal(percent));
        r.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        return r;
    }

    static class PaidRecorder implements OrderEventListener {
        private final List<OrderEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void onOrderEvent(OrderEvent event) {
            received.add(event);
        }

        void clear() {
            received.clear();
        }

        List<OrderPaidEvent> paid() {
            return received.stream().filter(OrderPaidEvent.class::isInstance)
                    .map(OrderPaidEvent.class::cast).toList();
        }
    }

    @TestConfiguration
    static class PaidRecorderConfig {
        @Bean
        PaidRecorder paidRecorder() {
            return new PaidRecorder();
        }
    }
}
