package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ecapi.domain.AppSetting;
import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.payment.CheckoutSession;
import com.example.ecapi.payment.ExpireResult;
import com.example.ecapi.payment.MinorUnits;
import com.example.ecapi.payment.PaymentCallback;
import com.example.ecapi.payment.PaymentProvider;
import com.example.ecapi.repository.AppSettingRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 決済側の開いている支払いの失効。Stripe は呼ばず、テストのクラスの中に閉じた
 * 偽の決済手段（id {@code fakepay}）で、呼ばれた回数・順序・呼ばれた時点の注文の状態を確かめる。
 *
 * <p>共有の H2 を汚さないよう、税の方式・送料は控えて戻し、作った注文・商品・カテゴリ・税率は自分で消す。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        // 振込への切り替え（振込と失効が重なる箇所）を確かめるため、振込を有効にする
        "app.payment.bank-transfer.enabled=true"
})
@Import(PaymentExpireTest.FakeProviderConfig.class)
class PaymentExpireTest {

    @Autowired PaymentService paymentService;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired SettingService settingService;
    @Autowired ShippingSettings shippingSettings;
    @Autowired AppSettingRepository appSettingRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired FakeExpiringProvider fake;
    @Autowired MutableClock clock;

    private static final List<String> SETTING_KEYS =
            List.of("tax.pricing-mode", "shipping.fee", "shipping.free-threshold");
    private final Map<String, String> savedRows = new HashMap<>();
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> taxRateIds = new ArrayList<>();
    private Category category;
    private Product product;
    private ListAppender<ILoggingEvent> orderLog;

    @BeforeEach
    void setUp() {
        savedRows.clear();
        for (String key : SETTING_KEYS) {
            savedRows.put(key, appSettingRepository.findById(key).map(AppSetting::getValue).orElse(null));
        }
        settingService.setPricingMode(PricingMode.INCLUSIVE);
        shippingSettings.setFee(BigDecimal.ZERO);
        shippingSettings.setFreeThreshold(BigDecimal.ZERO);
        fake.reset();
        clock.offset = Duration.ZERO;

        TaxRate r = new TaxRate();
        r.setCategory(TaxCategory.STANDARD);
        r.setRatePercent(new BigDecimal("10.00"));
        r.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        taxRateIds.add(taxRateRepository.save(r).getId());

        Category c = new Category();
        c.setName("ec12");
        c.setSlug("ec12-" + UUID.randomUUID());
        category = categoryRepository.save(c);

        Product p = new Product();
        p.setName("テスト商品");
        p.setDescription("テスト商品");
        p.setPrice(new BigDecimal("1000"));
        p.setStock(10);
        p.setTaxCategory(TaxCategory.STANDARD);
        p.setCategory(category);
        product = productRepository.save(p);
        productIds.add(product.getId());

        orderLog = new ListAppender<>();
        orderLog.start();
        ((Logger) LoggerFactory.getLogger(OrderService.class)).addAppender(orderLog);
    }

    @AfterEach
    void restore() {
        ((Logger) LoggerFactory.getLogger(OrderService.class)).detachAppender(orderLog);
        orderIds.forEach(orderRepository::deleteById);
        productIds.forEach(productRepository::deleteById);
        categoryRepository.deleteById(category.getId());
        taxRateIds.forEach(taxRateRepository::deleteById);
        savedRows.forEach((key, value) -> {
            if (value != null) {
                appSettingRepository.save(new AppSetting(key, value));
            } else if (appSettingRepository.existsById(key)) {
                appSettingRepository.deleteById(key);
            }
        });
    }

    // --- 支払いの始め直し -------------------------------------------------------

    @Test
    @DisplayName("restartingPaymentExpiresPreviousSession: 始め直すと、作る前に前の参照を失効させる。払われていれば作らない")
    void restartingPaymentExpiresPreviousSession() {
        Order order = place();
        String first = start(order).reference();
        assertThat(fake.expired).isEmpty();

        String second = start(order).reference();
        assertThat(fake.expired).containsExactly(first);
        assertThat(fake.events).containsExactly("create", "expire", "create");
        assertThat(reference(order)).isEqualTo(second);

        fake.result = ExpireResult.ALREADY_COMPLETED;
        int createdBefore = fake.created.get();
        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class);
        assertThat(fake.created.get()).isEqualTo(createdBefore);
        assertThat(reference(order)).isEqualTo(second);
    }

    @Test
    @DisplayName("lockFailureBecomesRetryableConflict: ロックの失敗（デッドロックなど）は 500 ではなく 409「混み合っています」")
    void lockFailureBecomesRetryableConflict() {
        Order order = place();
        fake.onCreate = () -> {
            throw new org.springframework.dao.CannotAcquireLockException("test");
        };

        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class)
                .hasMessage(BankTransferGuard.BUSY_MESSAGE);
        assertThat(reference(order)).isNull();
    }

    @Test
    @DisplayName("restartDoesNotRevertCancel: 外部を呼んでいる間に取り消されても、PENDING に戻さない")
    void restartDoesNotRevertCancel() {
        Order order = place();
        fake.onCreate = () -> inNewTx(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED));

        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class);

        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reference(order)).isNull();
        assertThat(fake.expired).hasSize(1);   // 作った新しいページが失効させられた
        assertThat(reserved()).isZero();
    }

    @Test
    @DisplayName("restartDoesNotRevertExpiry: 外部を呼んでいる間に期限切れになっても、PENDING に戻さない")
    void restartDoesNotRevertExpiry() {
        Order order = place();
        fake.onCreate = () -> inNewTx(() -> orderService.expireStalePendingOrders(Instant.now().plusSeconds(1)));

        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class);

        assertThat(status(order)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reserved()).isZero();
        assertThat(fake.expired).hasSize(1);
    }

    @Test
    @DisplayName("restartDoesNotRevertPaid: 外部を呼んでいる間に入金されても、PENDING に戻さない")
    void restartDoesNotRevertPaid() {
        Order order = place();
        long amount = MinorUnits.of(order.getTotalAmount(), "jpy");
        fake.onCreate = () -> inNewTx(() -> {
            orderService.confirmPayment(
                    new PaymentCallback(order.getId(), "cs_test_paid", amount, "jpy", true, "evt_1"),
                    "stripe", "jpy");
            return null;
        });

        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class);

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
        assertThat(reference(order)).isEqualTo("cs_test_paid");
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(9);
        assertThat(reserved()).isZero();
    }

    @Test
    @DisplayName("concurrentRestartKeepsOneOpenPage: 2 つの始め直しが重なっても、開いたままのページは 1 つ")
    void concurrentRestartKeepsOneOpenPage() {
        Order order = place();
        List<String> inner = new ArrayList<>();
        fake.onCreate = () -> inner.add(inNewTx(() -> start(order)).reference());

        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class);

        assertThat(inner).hasSize(1);
        assertThat(reference(order)).isEqualTo(inner.get(0));   // 先に書いた方が残る
        assertThat(fake.expired).hasSize(1);
        assertThat(fake.expired).doesNotContain(inner.get(0));   // 後から作った方だけが失効させられた
    }

    // --- 振込への切り替え（振込の抑えと失効が重なる箇所） ---------------------

    @Test
    @DisplayName("switchingToBankTransferExpiresCardSession: 振込に切り替えると、商品の行のロックを取る前に前の参照を失効させる")
    void switchingToBankTransferExpiresCardSession() throws Exception {
        Order order = place();
        String cardRef = start(order).reference();
        boolean[] lockHeldAtExpire = {false};
        fake.onExpire = () -> lockHeldAtExpire[0] = otherThreadBlockedOnProduct();

        var response = startAs(order, "bank_transfer");

        assertThat(fake.expired).containsExactly(cardRef);
        assertThat(lockHeldAtExpire[0]).as("失効の時点で商品の行のロックを持っていない").isFalse();
        assertThat(response.reference()).isEqualTo("BT-%06d".formatted(order.getId()));
        assertThat(provider(order)).isEqualTo("bank_transfer");
    }

    @Test
    @DisplayName("switchingToBankTransferExpiresCardSession: 払われている・失効できないときは切り替えを断り、断られた後も参照は前のまま")
    void switchingIsRefusedWhenExpireCannotClose() {
        Order order = place();
        String cardRef = start(order).reference();

        fake.result = ExpireResult.ALREADY_COMPLETED;
        assertThatThrownBy(() -> startAs(order, "bank_transfer")).isInstanceOf(ConflictException.class);
        fake.result = ExpireResult.FAILED;
        assertThatThrownBy(() -> startAs(order, "bank_transfer")).isInstanceOf(BadRequestException.class);
        assertThat(reference(order)).isEqualTo(cardRef);
        assertThat(provider(order)).isEqualTo("fakepay");

        // 失効の後に 商品ごとの上限 で断られた場合も、注文は前のまま（前のページは失効済み）
        fake.result = ExpireResult.EXPIRED;
        Product small = productRepository.findById(product.getId()).orElseThrow();
        small.setStock(4);
        productRepository.save(small);
        Order other = place();
        startAs(other, "bank_transfer");   // 下駄の 1 点
        assertThatThrownBy(() -> startAs(order, "bank_transfer")).isInstanceOf(ConflictException.class);
        assertThat(reference(order)).isEqualTo(cardRef);
        assertThat(provider(order)).isEqualTo("fakepay");
    }

    @Test
    @DisplayName("振込を選んだ注文でカードを始められるのは、作成から保留の分数まで")
    void cardIsRefusedAfterHoldWindowForBankTransferOrder() {
        Order order = place();
        startAs(order, "bank_transfer");

        clock.offset = Duration.ofMinutes(31);

        assertThatThrownBy(() -> start(order)).isInstanceOf(ConflictException.class);
        assertThat(provider(order)).isEqualTo("bank_transfer");
        assertThat(fake.created.get()).isZero();
    }

    // --- 管理画面の取り消し（失効してから取り消す） -----------------------------

    @Test
    @DisplayName("adminCancelExpires: 取り消しの前に、PENDING のうちに失効させる")
    void adminCancelExpires() {
        Order order = place();
        String ref = start(order).reference();
        fake.statusAtExpire.clear();

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(fake.expired).containsExactly(ref);
        assertThat(fake.statusAtExpire).containsExactly(OrderStatus.PENDING);
        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reserved()).isZero();
    }

    @Test
    @DisplayName("customerCancelExpires: お客さまの取り消しでも、同じ順序（PENDING のうちに失効 → 取り消し）で失効させる")
    void customerCancelExpires() {
        Order order = place();
        String ref = start(order).reference();
        fake.statusAtExpire.clear();

        orderService.cancelByGuest(order.getId(), order.getOrderToken());

        assertThat(fake.expired).containsExactly(ref);
        assertThat(fake.statusAtExpire).containsExactly(OrderStatus.PENDING);
        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reserved()).isZero();

        // もう払われている・失効できないときは取り消さない
        Order second = place();
        start(second);
        fake.result = ExpireResult.ALREADY_COMPLETED;
        assertThatThrownBy(() -> orderService.cancelByGuest(second.getId(), second.getOrderToken()))
                .isInstanceOf(ConflictException.class);
        fake.result = ExpireResult.FAILED;
        assertThatThrownBy(() -> orderService.cancelByGuest(second.getId(), second.getOrderToken()))
                .isInstanceOf(ConflictException.class);
        assertThat(status(second)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("adminCancelStopsWhenPaymentAlreadyCompleted: もう払われているときは取り消さない")
    void adminCancelStopsWhenPaymentAlreadyCompleted() {
        Order order = place();
        start(order);
        fake.result = ExpireResult.ALREADY_COMPLETED;

        assertThatThrownBy(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED))
                .isInstanceOf(ConflictException.class);

        assertThat(status(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(reserved()).isEqualTo(1);

        orderService.confirmPayment(new PaymentCallback(order.getId(), reference(order),
                MinorUnits.of(order.getTotalAmount(), "jpy"), "jpy", true, "evt_2"), "fakepay", "jpy");
        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("adminCancelWhenExpireFails: 失効できないときは取り消さない")
    void adminCancelWhenExpireFails() {
        Order order = place();
        start(order);
        fake.result = ExpireResult.FAILED;

        assertThatThrownBy(() -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED))
                .isInstanceOf(ConflictException.class);

        assertThat(status(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(reserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("bankTransferCancelNeedsNoExpire: 振込の注文の取り消しは、そのまま取り消せる")
    void bankTransferCancelNeedsNoExpire() {
        Order order = place();
        Order stored = orderRepository.findById(order.getId()).orElseThrow();
        stored.setPaymentProvider("bank_transfer");
        stored.setPaymentReference("BT-%06d".formatted(order.getId()));
        orderRepository.save(stored);

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fake.expired).isEmpty();
        assertThat(reserved()).isZero();
    }

    // --- 管理画面で PAID（振込の入金確認）の後 -------------------------------------

    @Test
    @DisplayName("manualPaidExpiresOpenPaymentAfterCommit: PAID のコミットの後に 1 回だけ失効させる。結果で PAID を止めない")
    void manualPaidExpiresOpenPaymentAfterCommit() {
        Order order = place();
        String ref = start(order).reference();
        fake.statusAtExpire.clear();

        orderService.updateStatus(order.getId(), OrderStatus.PAID);

        assertThat(fake.expired).containsExactly(ref);
        assertThat(fake.statusAtExpire).containsExactly(OrderStatus.PAID);
        assertThat(status(order)).isEqualTo(OrderStatus.PAID);

        // もう払われていた: PAID のまま・在庫は 1 回だけ確定・要返金確認の警告
        Order second = place();
        start(second);
        fake.result = ExpireResult.ALREADY_COMPLETED;
        orderService.updateStatus(second.getId(), OrderStatus.PAID);
        assertThat(status(second)).isEqualTo(OrderStatus.PAID);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(8);
        assertThat(warnings("PAYMENT_PAID_TWICE")).hasSize(1);

        // 失効できなかった: PAID のまま・警告
        Order third = place();
        start(third);
        fake.result = ExpireResult.FAILED;
        orderService.updateStatus(third.getId(), OrderStatus.PAID);
        assertThat(status(third)).isEqualTo(OrderStatus.PAID);
        assertThat(warnings("PAYMENT_EXPIRE_FAILED")).hasSize(1);
    }

    // --- 期限切れの掃除（コミットの後に 1 件ずつ失効させる） -----------------------------------

    @Test
    @DisplayName("expirySweepExpiresAfterCommit: 期限切れにした注文ごとに、コミットの後に失効させる")
    void expirySweepExpiresAfterCommit() {
        Order order = place();
        String ref = start(order).reference();
        fake.statusAtExpire.clear();

        orderService.expireStalePendingOrders(Instant.now().plusSeconds(1));

        assertThat(status(order)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fake.expired).containsExactly(ref);
        assertThat(fake.statusAtExpire).containsExactly(OrderStatus.EXPIRED);

        // 失効できなくても期限切れのまま
        Order second = place();
        start(second);
        fake.result = ExpireResult.FAILED;
        orderService.expireStalePendingOrders(Instant.now().plusSeconds(1));
        assertThat(status(second)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(warnings("PAYMENT_EXPIRE_FAILED")).hasSize(1);
    }

    // --- helpers --------------------------------------------------------------

    private Order place() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(product.getId(), 1));
        orderIds.add(order.getId());
        return order;
    }

    private com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse start(Order order) {
        return startAs(order, "fakepay");
    }

    private com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse startAs(Order order, String provider) {
        return paymentService.createGuestCheckoutSession(order.getId(), order.getOrderToken(), provider, null);
    }

    private String provider(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getPaymentProvider();
    }

    /**
     * 別のスレッド（別のトランザクション）から同じ商品の行を更新しに行き、待たされたら true。
     * 失効の呼び出しの時点で、呼び出し側が商品の行のロックを持っているかを調べる。
     */
    private boolean otherThreadBlockedOnProduct() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> probe = pool.submit(() -> inNewTx(
                    () -> productRepository.reserveStock(product.getId(), 0, 100)));
            try {
                probe.get(1500, TimeUnit.MILLISECONDS);
                return false;
            } catch (TimeoutException e) {
                return true;
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        } finally {
            pool.shutdown();
        }
    }

    private <T> T inNewTx(java.util.function.Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(s -> action.get());
    }

    private OrderStatus status(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private String reference(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getPaymentReference();
    }

    private int reserved() {
        return productRepository.findById(product.getId()).orElseThrow().getReserved();
    }

    private List<ILoggingEvent> warnings(String key) {
        return orderLog.list.stream()
                .filter(e -> e.getFormattedMessage().contains(key)).toList();
    }

    /** 失効の呼び出しを記録する偽の決済手段。このクラスの中だけで登録する。 */
    static class FakeExpiringProvider implements PaymentProvider {
        final OrderRepository orders;
        final AtomicInteger created = new AtomicInteger();
        final List<String> expired = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<OrderStatus> statusAtExpire = new CopyOnWriteArrayList<>();
        volatile ExpireResult result = ExpireResult.EXPIRED;
        volatile Runnable onCreate;
        volatile Runnable onExpire;

        FakeExpiringProvider(OrderRepository orders) {
            this.orders = orders;
        }

        void reset() {
            created.set(0);
            expired.clear();
            events.clear();
            statusAtExpire.clear();
            result = ExpireResult.EXPIRED;
            onCreate = null;
            onExpire = null;
        }

        @Override
        public String id() {
            return "fakepay";
        }

        @Override
        public String displayName() {
            return "テスト用";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public CheckoutSession createSession(Order order) {
            Runnable hook = onCreate;
            onCreate = null;
            if (hook != null) {
                hook.run();
            }
            events.add("create");
            return new CheckoutSession("fake_" + created.incrementAndGet() + "_" + UUID.randomUUID(), "/fake");
        }

        @Override
        public ExpireResult expireOpenPayment(Order order) {
            Runnable hook = onExpire;
            if (hook != null) {
                hook.run();
            }
            events.add("expire");
            expired.add(order.getPaymentReference());
            statusAtExpire.add(orders.findById(order.getId()).orElseThrow().getStatus());
            return result;
        }
    }

    /** 時刻を進められる時計。支払いの期限の判定（カードを始められる期間）を確かめるために差し替える。 */
    static class MutableClock extends Clock {
        volatile Duration offset = Duration.ZERO;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }
    }

    @TestConfiguration
    static class FakeProviderConfig {
        @Bean
        FakeExpiringProvider fakeExpiringProvider(OrderRepository orders) {
            return new FakeExpiringProvider(orders);
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
