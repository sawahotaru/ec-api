package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.domain.User;
import com.example.ecapi.event.OrderCancelledEvent;
import com.example.ecapi.event.OrderEvent;
import com.example.ecapi.event.OrderEventListener;
import com.example.ecapi.event.OrderExpiredEvent;
import com.example.ecapi.event.OrderPaidEvent;
import com.example.ecapi.event.OrderPlacedEvent;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.payment.MinorUnits;
import com.example.ecapi.payment.PaymentCallback;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.CouponRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 注文の在庫ライフサイクル（引当 → 確定 or 解放）の回帰テスト。
 *
 * <p>ここが最優先で必要な理由は、過去に実際に踏んだ2つの事故がいずれも
 * <strong>この境界</strong>で起きたため:
 * <ol>
 *   <li>管理画面から手で PAID にすると引当（reserved）が実在庫の減算に変換されず、
 *       在庫が過大に残っていた（Webhook 経由だけが正しく処理していた）。</li>
 *   <li>Webhook の重複配信で二重に在庫が減る可能性（冪等性が PENDING ガード頼み）。</li>
 * </ol>
 *
 * <p>クラスに {@code @Transactional} を<strong>付けていない</strong>のは意図的。
 * 注文イベントは {@code AFTER_COMMIT} で配られるので、テストがトランザクションを
 * 抱えたままだとリスナーが1度も走らず、「重複配信でメールが2通飛ぶ」類の退行を
 * 検出できなくなる。後片付けは {@link #reset()} で明示的に行う。
 */
@SpringBootTest(properties = {
        // シードは切って、各テストが自分でフィクスチャを作る（件数・在庫を固定するため）
        "app.seed.enabled=false",
        // 期限切れスイープが裏で走ってテストの引当を横取りしないよう、実質無効化する
        "app.order.expiry-sweep-ms=3600000"
})
@Import(OrderPaymentLifecycleTest.RecordingListenerConfig.class)
class OrderPaymentLifecycleTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired UserRepository userRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired RecordingOrderEventListener recorder;
    @Autowired CouponRepository couponRepository;
    @Autowired PlatformTransactionManager txManager;

    private User buyer;
    private Product matcha;   // 1,620円 / 軽減8% / 在庫10
    private Product yunomi;   // 2,750円 / 標準10% / 在庫3

    @BeforeEach
    void reset() {
        cartItemRepository.deleteAll();
        orderRepository.deleteAll();
        couponRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
        taxRateRepository.deleteAll();
        recorder.clear();

        taxRateRepository.save(taxRate(TaxCategory.STANDARD, "10.00"));
        taxRateRepository.save(taxRate(TaxCategory.REDUCED, "8.00"));

        Category category = new Category();
        category.setName("テスト");
        category.setSlug("test");
        category = categoryRepository.save(category);

        matcha = product("宇治抹茶 30g", "1620", 10, TaxCategory.REDUCED, category);
        yunomi = product("藍染湯呑み 二客組", "2750", 3, TaxCategory.STANDARD, category);

        buyer = new User();
        buyer.setEmail("buyer@example.com");
        buyer.setPassword("x");
        buyer.setName("Buyer");
        buyer.setRole(Role.USER);
        buyer = userRepository.save(buyer);
    }

    // --- checkout: 引当であって減算ではない ------------------------------------

    @Test
    @DisplayName("checkout は在庫を引き当てるだけで、実在庫は減らさない")
    void checkoutHoldsStockWithoutDecrementing() {
        addToCart(matcha, 2);
        addToCart(yunomi, 1);

        Order order = orderService.checkout(buyer);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stock(matcha)).isEqualTo(10);
        assertThat(reserved(matcha)).isEqualTo(2);
        assertThat(available(matcha)).isEqualTo(8);
        assertThat(reserved(yunomi)).isEqualTo(1);

        // 税区分をまたいだ内訳（内税・切り捨て）: 3,240の8% = 240 / 2,750の10% = 250
        assertThat(order.getTaxAmount()).isEqualByComparingTo("490");
        assertThat(order.getSubtotalAmount()).isEqualByComparingTo("5500");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("5990");

        // カートは注文に変換されて空になる
        assertThat(cartItemRepository.findByUserId(buyer.getId())).isEmpty();
    }

    @Test
    @DisplayName("引当を超える数量は拒否され、同一注文の他の行の引当も巻き戻る")
    void insufficientStockRollsBackEarlierHolds() {
        Map<Long, Integer> lines = new LinkedHashMap<>();
        lines.put(matcha.getId(), 2);   // ここは通る
        lines.put(yunomi.getId(), 4);   // 在庫3なので失敗する

        assertThatThrownBy(() -> orderService.guestCheckout("guest@example.com", lines))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("藍染湯呑み");

        // 先に成功した行の引当が残っていると、以後ずっと売れない在庫になる
        assertThat(reserved(matcha)).isZero();
        assertThat(reserved(yunomi)).isZero();
        assertThat(orderRepository.count()).isZero();
    }

    // --- applyPaid: 冪等性（Webhook 重複配信）----------------------------------

    @Test
    @DisplayName("同じ支払い確定が2回届いても、在庫は1回だけ減り、paid イベントも1回だけ")
    void duplicatePaymentConfirmationIsIdempotent() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);

        orderService.markPaid(order.getId(), "stripe", "cs_test_123");
        orderService.markPaid(order.getId(), "stripe", "cs_test_123");  // Webhook 再送

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(matcha)).isEqualTo(8);      // 10 - 2。二重に減っていないこと
        assertThat(reserved(matcha)).isZero();
        assertThat(available(matcha)).isEqualTo(8);
        assertThat(recorder.paidEvents()).hasSize(1);
    }

    @Test
    @DisplayName("支払い確定で引当が実在庫の減算に変換される（決済参照IDも記録される）")
    void paymentConvertsHoldIntoDecrement() {
        addToCart(matcha, 3);
        Order order = orderService.checkout(buyer);

        orderService.markPaid(order.getId(), "stripe", "cs_test_abc");

        Order paid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getPaymentProvider()).isEqualTo("stripe");
        assertThat(paid.getPaymentReference()).isEqualTo("cs_test_abc");
        assertThat(stock(matcha)).isEqualTo(7);
        assertThat(reserved(matcha)).isZero();
    }

    @Test
    @DisplayName("存在しない注文への支払い通知は例外にせず握り潰す（Webhook を無限再送させない）")
    void paymentForUnknownOrderIsIgnored() {
        orderService.markPaid(999_999L, "stripe", "cs_test_missing");

        assertThat(recorder.paidEvents()).isEmpty();
    }

    // --- applyPaid: EXPIRED 後に入金が届いた分岐 -------------------------------

    @Test
    @DisplayName("期限切れで引当を解放した後に入金が届いたら、PAID にはするが在庫は触らない")
    void paymentAfterExpiryDoesNotTouchStock() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);

        int expired = orderService.expireStalePendingOrders(Instant.now().plusSeconds(60));

        assertThat(expired).isEqualTo(1);
        assertThat(status(order)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reserved(matcha)).isZero();       // 引当は解放済み
        assertThat(stock(matcha)).isEqualTo(10);
        assertThat(recorder.expiredEvents()).hasSize(1);

        // 解放後に決済が確定した——ここで再コミットすると売り越す
        orderService.markPaid(order.getId(), "stripe", "cs_test_late");

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(matcha)).isEqualTo(10);     // 減らさない（棚卸し調整は人手）
        assertThat(reserved(matcha)).isZero();       // マイナスにもしない
        assertThat(recorder.paidEvents()).hasSize(1);
    }

    // --- 管理画面からのステータス変更 -----------------------------------------

    @Test
    @DisplayName("管理画面から手動で PAID にしても、引当が実在庫の減算に変換される（過去の在庫バグの回帰）")
    void manualAdminPaidCommitsStock() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);

        orderService.updateStatus(order.getId(), OrderStatus.PAID);

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(matcha)).isEqualTo(8);
        assertThat(reserved(matcha)).isZero();
        assertThat(recorder.paidEvents()).hasSize(1);
        // 手動確定なので決済手段は空のまま（既存の値を上書きしない）
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentProvider()).isNull();
    }

    @Test
    @DisplayName("未払い注文のキャンセルは引当を戻す")
    void cancellingPendingOrderReleasesHold() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(matcha)).isEqualTo(10);
        assertThat(reserved(matcha)).isZero();
        assertThat(available(matcha)).isEqualTo(10);
        assertThat(recorder.cancelledEvents()).hasSize(1);
    }

    @Test
    @DisplayName("支払い済み注文のキャンセルでは在庫を戻さない（返品は別の業務）")
    void cancellingPaidOrderDoesNotRestock() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);
        orderService.markPaid(order.getId(), "stripe", "cs_test_paid");

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(matcha)).isEqualTo(8);   // 減算済みのまま。二重に増えない
        assertThat(reserved(matcha)).isZero();
    }

    @Test
    @DisplayName("同じステータスへの変更は何も起こさない（PAID の再適用で在庫が二重に減らない）")
    void updatingToSameStatusIsNoOp() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);
        orderService.updateStatus(order.getId(), OrderStatus.PAID);

        orderService.updateStatus(order.getId(), OrderStatus.PAID);

        assertThat(stock(matcha)).isEqualTo(8);
        assertThat(recorder.paidEvents()).hasSize(1);
    }

    @Test
    @DisplayName("期限切れスイープは PENDING だけを対象にする（支払い済みを巻き込まない）")
    void expirySweepSkipsPaidOrders() {
        addToCart(matcha, 2);
        Order paidOrder = orderService.checkout(buyer);
        orderService.markPaid(paidOrder.getId(), "stripe", "cs_test_ok");

        int expired = orderService.expireStalePendingOrders(Instant.now().plusSeconds(60));

        assertThat(expired).isZero();
        assertThat(status(paidOrder)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(matcha)).isEqualTo(8);
    }

    // --- 支払い済み・キャンセル済みの注文への通知 -----------------------------

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "DELIVERED"})
    @DisplayName("支払い済み以降の注文に別セッションの入金が届いても、参照を上書きしない")
    void paidOrderKeepsItsReferenceWhenAnotherSessionPays(OrderStatus state) {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);
        orderService.markPaid(order.getId(), "stripe", "cs_test_a");
        if (state != OrderStatus.PAID) {
            orderService.updateStatus(order.getId(), state);
        }

        orderService.markPaid(order.getId(), "stripe", "cs_test_b");

        Order stored = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(state);
        assertThat(stored.getPaymentReference()).isEqualTo("cs_test_a");
        assertThat(stock(matcha)).isEqualTo(8);
        assertThat(reserved(matcha)).isZero();
        assertThat(recorder.paidEvents()).hasSize(1);
    }

    @Test
    @DisplayName("キャンセル済みの注文に入金が届いても、参照を上書きせず状態・在庫も変えない")
    void cancelledOrderKeepsItsReferenceAndFlagsRefund() {
        addToCart(matcha, 2);
        Order order = orderService.checkout(buyer);
        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);

        orderService.markPaid(order.getId(), "stripe", "cs_test_late");

        Order stored = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stored.getPaymentReference()).isNull();
        assertThat(stored.getPaymentProvider()).isNull();
        assertThat(stock(matcha)).isEqualTo(10);
        assertThat(reserved(matcha)).isZero();
        assertThat(recorder.paidEvents()).isEmpty();
    }

    // --- ゲスト購入 -----------------------------------------------------------

    @Test
    @DisplayName("ゲスト購入も同じ引当→確定の経路を通り、照会用トークンが発行される")
    void guestCheckoutFollowsTheSameStockPath() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 2));

        assertThat(order.getUser()).isNull();
        assertThat(order.getOrderToken()).isNotBlank();
        assertThat(reserved(matcha)).isEqualTo(2);
        assertThat(stock(matcha)).isEqualTo(10);

        orderService.markPaid(order.getId(), "bank_transfer", "REF-001");

        assertThat(status(order)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(matcha)).isEqualTo(8);
        assertThat(reserved(matcha)).isZero();
        // イベントの連絡先はゲストの入力メール（会員なら口座メール）
        assertThat(recorder.paidEvents()).singleElement()
                .extracting(OrderPaidEvent::contactEmail).isEqualTo("guest@example.com");
    }

    // --- 注文成立の通知（ゲストが照会トークンを持ち帰る唯一の経路） -------------

    @Test
    @DisplayName("ゲスト購入の placed イベントは照会トークンを運ぶ（確認メールの照会リンクの素）")
    void guestCheckoutPublishesPlacedEventWithItsToken() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 2));

        assertThat(recorder.placedEvents()).singleElement().satisfies(placed -> {
            assertThat(placed.orderId()).isEqualTo(order.getId());
            assertThat(placed.contactEmail()).isEqualTo("guest@example.com");
            assertThat(placed.totalAmount()).isEqualByComparingTo("3240");
            // ここが空になると、ゲストは注文に戻る手段を完全に失う
            assertThat(placed.guestToken()).isEqualTo(order.getOrderToken()).isNotBlank();
        });
    }

    @Test
    @DisplayName("会員の checkout も placed を発行するが、トークンは載せない")
    void memberCheckoutPublishesPlacedEventWithoutAToken() {
        addToCart(matcha, 1);

        orderService.checkout(buyer);

        assertThat(recorder.placedEvents()).singleElement().satisfies(placed -> {
            assertThat(placed.contactEmail()).isEqualTo("buyer@example.com");
            assertThat(placed.guestToken()).isNull();
        });
    }

    @Test
    @DisplayName("在庫不足でロールバックした注文では placed は配られない（AFTER_COMMIT の契約）")
    void rolledBackCheckoutPublishesNothing() {
        Map<Long, Integer> lines = new LinkedHashMap<>();
        lines.put(yunomi.getId(), 4);   // 在庫3

        assertThatThrownBy(() -> orderService.guestCheckout("guest@example.com", lines))
                .isInstanceOf(BadRequestException.class);

        assertThat(recorder.placedEvents()).isEmpty();
    }

    // --- ゲスト注文の照会（トークンがログインの代わりになる） -------------------
    // 画面（#/orders/guest）がこの経路に乗るので、「トークンが鍵として正しく効くか」を固定する。

    @Test
    @DisplayName("ゲスト注文は 注文ID + 正しいトークン で引ける")
    void guestOrderCanBeLookedUpWithItsToken() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));

        Order found = orderService.getGuestOrder(order.getId(), order.getOrderToken());

        assertThat(found.getId()).isEqualTo(order.getId());
        assertThat(found.getContactEmail()).isEqualTo("guest@example.com");
        assertThat(found.getItems()).singleElement()
                .extracting(item -> item.getProductName()).isEqualTo("宇治抹茶 30g");
    }

    @Test
    @DisplayName("トークンが違えば引けない（注文IDを総当たりされても中身は漏れない）")
    void guestOrderLookupRejectsAWrongToken() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));

        assertThatThrownBy(() -> orderService.getGuestOrder(order.getId(), "not-the-token"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("他人の注文のトークンでは引けない（トークンは注文ごとに紐づく）")
    void guestOrderLookupIsScopedToItsOwnOrder() {
        Order mine = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 1));
        Order theirs = orderService.guestCheckout("b@example.com", Map.of(matcha.getId(), 1));

        assertThat(mine.getOrderToken()).isNotEqualTo(theirs.getOrderToken());
        assertThatThrownBy(() -> orderService.getGuestOrder(mine.getId(), theirs.getOrderToken()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("トークン未指定は 400（空文字で全件が引けたりしない）")
    void guestOrderLookupRequiresAToken() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));

        assertThatThrownBy(() -> orderService.getGuestOrder(order.getId(), "  "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> orderService.getGuestOrder(order.getId(), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("期限切れ後も注文自体は照会できる（状態が EXPIRED として見える）")
    void expiredGuestOrderIsStillVisibleToItsOwner() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 2));
        orderService.expireStalePendingOrders(Instant.now().plusSeconds(60));

        Order found = orderService.getGuestOrder(order.getId(), order.getOrderToken());

        assertThat(found.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reserved(matcha)).isZero();   // 引当は解放済み
    }

    // --- お客さまの取り消し ---------------------------------------------

    @Test
    @DisplayName("customerCancelReleasesHoldAndCoupon: 取り消すと引当とクーポンが戻り、取り消しのイベントが 1 件")
    void customerCancelReleasesHoldAndCoupon() {
        try {
            checkCancelReleasesHoldAndCoupon();
        } finally {
            couponRepository.deleteAll();   // 共有の H2 にクーポンを残さない
        }
    }

    private void checkCancelReleasesHoldAndCoupon() {
        save(coupon("SAVE500", DiscountType.FIXED, "500"));
        Order guest = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 2), "SAVE500");
        assertThat(reserved(matcha)).isEqualTo(2);
        assertThat(reload("SAVE500").getRedeemedCount()).isEqualTo(1);
        recorder.clear();

        Order cancelled = orderService.cancelByGuest(guest.getId(), guest.getOrderToken());

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(status(guest)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reserved(matcha)).isZero();
        assertThat(stock(matcha)).isEqualTo(10);
        assertThat(reload("SAVE500").getRedeemedCount()).isZero();
        assertThat(recorder.cancelledEvents()).hasSize(1);

        // 会員の注文も同じ
        addToCart(matcha, 1);
        Order mine = orderService.checkout(buyer);
        assertThat(reserved(matcha)).isEqualTo(1);
        orderService.cancelByMember(buyer, mine.getId());
        assertThat(status(mine)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reserved(matcha)).isZero();
        assertThat(recorder.cancelledEvents()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "SHIPPED", "DELIVERED", "CANCELLED", "EXPIRED"})
    @DisplayName("customerCannotCancelNonPending: お支払い待ち以外は取り消せない（409）。状態・在庫・引当は変わらない")
    void customerCannotCancelNonPending(OrderStatus status) {
        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        Order stored = orderRepository.findById(order.getId()).orElseThrow();
        stored.setStatus(status);
        orderRepository.save(stored);
        int stockBefore = stock(matcha);
        int reservedBefore = reserved(matcha);

        assertThatThrownBy(() -> orderService.cancelByGuest(order.getId(), order.getOrderToken()))
                .isInstanceOf(ConflictException.class);

        assertThat(status(order)).isEqualTo(status);
        assertThat(stock(matcha)).isEqualTo(stockBefore);
        assertThat(reserved(matcha)).isEqualTo(reservedBefore);
    }

    @Test
    @DisplayName("customerCannotCancelOthersOrder: 他人の注文・誤ったトークンは 404、トークンが無ければ 400。何も変わらない")
    void customerCannotCancelOthersOrder() {
        addToCart(matcha, 1);
        Order mine = orderService.checkout(buyer);
        User other = new User();
        other.setEmail("other@example.com");
        other.setPassword("x");
        other.setName("Other");
        other.setRole(Role.USER);
        User savedOther = userRepository.save(other);
        Order guest = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        int reservedBefore = reserved(matcha);

        assertThatThrownBy(() -> orderService.cancelByMember(savedOther, mine.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> orderService.cancelByGuest(guest.getId(), "wrong-token"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> orderService.cancelByGuest(guest.getId(), " "))
                .isInstanceOf(BadRequestException.class);

        assertThat(status(mine)).isEqualTo(OrderStatus.PENDING);
        assertThat(status(guest)).isEqualTo(OrderStatus.PENDING);
        assertThat(reserved(matcha)).isEqualTo(reservedBefore);
    }

    @Test
    @DisplayName("paymentAfterCustomerCancelIsFlaggedNotApplied: 取り消した後の入金は、状態を変えず要返金確認になる")
    void paymentAfterCustomerCancelIsFlaggedNotApplied() {
        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        orderService.cancelByGuest(order.getId(), order.getOrderToken());
        recorder.clear();

        orderService.confirmPayment(new PaymentCallback(order.getId(), "cs_test_late",
                MinorUnits.of(order.getTotalAmount(), "jpy"), "jpy", true, "evt_late"), "stripe", "jpy");

        assertThat(status(order)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(matcha)).isEqualTo(10);
        assertThat(reserved(matcha)).isZero();
        assertThat(recorder.paidEvents()).isEmpty();
    }

    @Test
    @DisplayName("cancelAndPaymentDoNotBothApply: 取り消しと入金が重なっても、どちらか一方だけが効く")
    void cancelAndPaymentDoNotBothApply() throws Exception {
        Order order = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        PaymentCallback callback = new PaymentCallback(order.getId(), "cs_test_race",
                MinorUnits.of(order.getTotalAmount(), "jpy"), "jpy", true, "evt_race");
        CountDownLatch locked = new CountDownLatch(1);

        runTogether(
                () -> holdingLockThen(locked, () -> orderService.cancelByGuest(order.getId(), order.getOrderToken())),
                () -> {
                    locked.await();
                    orderService.confirmPayment(callback, "stripe", "jpy");
                    return null;
                });

        OrderStatus finalStatus = status(order);
        assertThat(finalStatus).isIn(OrderStatus.CANCELLED, OrderStatus.PAID);
        assertThat(reserved(matcha)).isZero();
        assertThat(stock(matcha)).isEqualTo(finalStatus == OrderStatus.PAID ? 9 : 10);
    }

    @Test
    @DisplayName("cancelAndExpiryDoNotBothRelease: 取り消しと期限切れの掃除が重なっても、引当は 1 回だけ戻る")
    void cancelAndExpiryDoNotBothRelease() throws Exception {
        Order x = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        Thread.sleep(10);
        Instant t = Instant.now();
        Thread.sleep(10);
        Order y = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        assertThat(reserved(matcha)).isEqualTo(2);
        CountDownLatch locked = new CountDownLatch(1);

        runTogether(
                () -> holdingLockThen(locked, () -> orderService.cancelByGuest(x.getId(), x.getOrderToken())),
                () -> {
                    locked.await();
                    orderService.expireStalePendingOrders(t);   // t より前に作られた x だけが対象
                    return null;
                });

        assertThat(status(x)).isEqualTo(OrderStatus.CANCELLED);   // 期限切れで上書きされない
        assertThat(status(y)).isEqualTo(OrderStatus.PENDING);
        assertThat(reserved(matcha)).isEqualTo(1);                // y の 1 点が残る（2 回解放されて 0 にならない）
    }

    @Test
    @DisplayName("adminCancelAndCustomerCancelDoNotBothRelease: お客さまの取り消しと管理画面の取り消しが重なっても、引当は 1 回だけ戻る")
    void adminCancelAndCustomerCancelDoNotBothRelease() throws Exception {
        Order x = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        Order y = orderService.guestCheckout("g@example.com", Map.of(matcha.getId(), 1));
        recorder.clear();
        CountDownLatch locked = new CountDownLatch(1);

        runTogether(
                () -> holdingLockThen(locked, () -> orderService.cancelByGuest(x.getId(), x.getOrderToken())),
                () -> {
                    locked.await();
                    orderService.updateStatus(x.getId(), OrderStatus.CANCELLED);
                    return null;
                });

        assertThat(status(x)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(status(y)).isEqualTo(OrderStatus.PENDING);
        assertThat(reserved(matcha)).isEqualTo(1);
        assertThat(recorder.cancelledEvents()).hasSize(1);
    }

    /** 取り消しのトランザクションがロックを取った後、300 ミリ秒そのまま持ち続けてからコミットする。 */
    private Object holdingLockThen(CountDownLatch locked, Runnable cancel) {
        return new TransactionTemplate(txManager).execute(s -> {
            cancel.run();
            locked.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
    }

    /** 2 つの処理を別のスレッドで同時に走らせ、10 秒で打ち切る。 */
    @SafeVarargs
    private static void runTogether(Callable<Object>... jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.length);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> job : jobs) {
                futures.add(pool.submit(job));
            }
            for (Future<Object> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --- helpers --------------------------------------------------------------

    private void addToCart(Product product, int quantity) {
        CartItem item = new CartItem();
        item.setUser(buyer);
        item.setProduct(product);
        item.setQuantity(quantity);
        cartItemRepository.save(item);
    }

    private Product product(String name, String price, int stock, TaxCategory tax, Category category) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(name);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setTaxCategory(tax);
        p.setCategory(category);
        return productRepository.save(p);
    }

    private TaxRate taxRate(TaxCategory category, String percent) {
        TaxRate r = new TaxRate();
        r.setCategory(category);
        r.setRatePercent(new BigDecimal(percent));
        r.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        return r;
    }

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

    private Product reload(Product p) {
        return productRepository.findById(p.getId()).orElseThrow();
    }

    private int stock(Product p) {
        return reload(p).getStock();
    }

    private int reserved(Product p) {
        return reload(p).getReserved();
    }

    private int available(Product p) {
        return reload(p).getAvailable();
    }

    private OrderStatus status(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    /**
     * プラグイン（{@link OrderEventListener}）として登録し、ディスパッチャ経由で実際に
     * 配られたイベントだけを数える。{@code ApplicationEvents} で publish を数える方式と違い、
     * 「AFTER_COMMIT で配られる」という契約ごと検証できる。
     */
    static class RecordingOrderEventListener implements OrderEventListener {

        private final List<OrderEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void onOrderEvent(OrderEvent event) {
            received.add(event);
        }

        void clear() {
            received.clear();
        }

        List<OrderPaidEvent> paidEvents() {
            return received.stream().filter(OrderPaidEvent.class::isInstance)
                    .map(OrderPaidEvent.class::cast).toList();
        }

        List<OrderExpiredEvent> expiredEvents() {
            return received.stream().filter(OrderExpiredEvent.class::isInstance)
                    .map(OrderExpiredEvent.class::cast).toList();
        }

        List<OrderPlacedEvent> placedEvents() {
            return received.stream().filter(OrderPlacedEvent.class::isInstance)
                    .map(OrderPlacedEvent.class::cast).toList();
        }

        List<OrderCancelledEvent> cancelledEvents() {
            return received.stream().filter(OrderCancelledEvent.class::isInstance)
                    .map(OrderCancelledEvent.class::cast).toList();
        }
    }

    @TestConfiguration
    static class RecordingListenerConfig {
        @Bean
        RecordingOrderEventListener recordingOrderEventListener() {
            return new RecordingOrderEventListener();
        }
    }
}
