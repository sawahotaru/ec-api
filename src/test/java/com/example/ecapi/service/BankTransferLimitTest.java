package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.TooManyAttemptsException;
import com.example.ecapi.payment.provider.BankTransferProperties;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.repository.UserRepository;
import com.example.ecapi.security.RequestRateLimiter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 銀行振込の長い取り置きの抑え（3 つの抑え）と、選び直しが冪等なこと。
 *
 * <p>共有の H2 を汚さないよう、作った注文・カート・商品・カテゴリ・会員・税率は自分で消し、
 * 変えた設定値（回数の上限）と回数の記録は戻す。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.payment.bank-transfer.enabled=true"
})
class BankTransferLimitTest {

    private static final String BANK = "bank_transfer";

    @Autowired PaymentService paymentService;
    @Autowired OrderService orderService;
    @Autowired BankTransferGuard guard;
    @Autowired BankTransferProperties props;
    @Autowired RequestRateLimiter limiter;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired UserRepository userRepository;
    @Autowired CartItemRepository cartItemRepository;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> taxRateIds = new ArrayList<>();
    private Category category;
    private int savedMaxPerSource;

    @BeforeEach
    void setUp() {
        savedMaxPerSource = props.getMaxPerSource();
        limiter.clear();

        TaxRate r = new TaxRate();
        r.setCategory(TaxCategory.STANDARD);
        r.setRatePercent(new BigDecimal("10.00"));
        r.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        taxRateIds.add(taxRateRepository.save(r).getId());

        Category c = new Category();
        c.setName("ec10");
        c.setSlug("ec10-" + UUID.randomUUID());
        category = categoryRepository.save(c);
    }

    @AfterEach
    void restore() {
        props.setMaxPerSource(savedMaxPerSource);
        limiter.clear();
        orderIds.forEach(orderRepository::deleteById);
        userIds.forEach(id -> {
            cartItemRepository.deleteAll(cartItemRepository.findByUserId(id));
            userRepository.deleteById(id);
        });
        productIds.forEach(productRepository::deleteById);
        categoryRepository.deleteById(category.getId());
        taxRateIds.forEach(taxRateRepository::deleteById);
    }

    // --- 商品ごとの上限: 商品ごとの上限（在庫の 20%。在庫が少ないときは合計 1 点まで） ------------

    @Test
    @DisplayName("商品ごとの上限: 在庫 10 の商品は、振込の取り置きが合計 2 点（20%）まで")
    void s1LimitsBankTransferHoldToPercentOfStock() {
        Product p = product(10);
        assertThat(bank(guestOrder(p))).isNotNull();   // 合計 1
        assertThat(bank(guestOrder(p))).isNotNull();   // 合計 2（上限ちょうど）

        Order third = guestOrder(p);
        assertThatThrownBy(() -> bank(third)).isInstanceOf(ConflictException.class)
                .hasMessage(BankTransferGuard.S1_MESSAGE);   // 合計 3
        assertThat(providerOf(third)).isNull();
    }

    @Test
    @DisplayName("商品ごとの上限: 在庫 4 の商品は、割合では 1 点も取り置けないので下駄で合計 1 点まで")
    void s1SmallStockAllowsOnlyOnePoint() {
        Product p = product(4);
        assertThat(bank(guestOrder(p))).isNotNull();

        assertThatThrownBy(() -> bank(guestOrder(p))).isInstanceOf(ConflictException.class)
                .hasMessage(BankTransferGuard.S1_MESSAGE);
    }

    // --- 会員の件数: 会員 1 人あたりの未払いの振込（ゲストには掛けない） -----------------------

    @Test
    @DisplayName("会員の件数: 同じ会員の 2 件目の振込は断る。この注文自身は数えない")
    void s2LimitsPendingBankTransfersPerMember() {
        Product p = product(10);
        User member = member();
        Order first = memberOrder(member, p);
        Order second = memberOrder(member, p);

        bank(first);
        assertThatThrownBy(() -> bank(second)).isInstanceOf(ConflictException.class)
                .hasMessage(BankTransferGuard.S2_MESSAGE);

        // 振込を選んだ注文自身は、数えない（自分が 1 件目なので、自分を足すと上限に当たってしまう）
        assertThatCode(() -> guard.checkMember(orderRepository.findById(first.getId()).orElseThrow()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.checkMember(orderRepository.findById(second.getId()).orElseThrow()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("会員の件数: ゲストには掛けない（同じメールで 2 件目の振込も通る）")
    void s2DoesNotApplyToGuests() {
        Product p = product(10);
        assertThat(bank(guestOrder(p))).isNotNull();
        assertThat(bank(guestOrder(p))).isNotNull();
    }

    // --- 送信元の回数: 同じ送信元の回数（最初に確かめる） ----------------------------------------

    @Test
    @DisplayName("送信元の回数: 同じ送信元の 2 回目を断る（429 の例外）。商品ごとの上限 より先に確かめる")
    void s3LimitsSelectionsPerSource() {
        props.setMaxPerSource(1);
        Product p = product(4);
        Order first = guestOrder(p);
        paymentService.createGuestCheckoutSession(first.getId(), first.getOrderToken(), BANK, "ip:test-a");

        // 2 件目は 商品ごとの上限 でも断られる状況。送信元の回数 が先なので、断る理由は 送信元の回数（回数）になる
        Order second = guestOrder(p);
        assertThatThrownBy(() -> paymentService.createGuestCheckoutSession(
                second.getId(), second.getOrderToken(), BANK, "ip:test-a"))
                .isInstanceOf(TooManyAttemptsException.class);

        // 別の送信元は数えない
        Product other = product(10);
        Order third = guestOrder(other);
        assertThat(paymentService.createGuestCheckoutSession(
                third.getId(), third.getOrderToken(), BANK, "ip:test-b")).isNotNull();
    }

    @Test
    @DisplayName("選び直し: 同じ注文で振込を選び直すと、3 つの抑え を数えずに同じ案内を返す")
    void reselectingBankTransferIsIdempotent() {
        props.setMaxPerSource(1);
        Product p = product(10);
        Order order = guestOrder(p);

        CheckoutSessionResponse first = paymentService.createGuestCheckoutSession(
                order.getId(), order.getOrderToken(), BANK, "ip:test-c");
        CheckoutSessionResponse again = paymentService.createGuestCheckoutSession(
                order.getId(), order.getOrderToken(), BANK, "ip:test-c");

        assertThat(again.reference()).isEqualTo(first.reference());
        assertThat(again.instructions()).isEqualTo(first.instructions());
        assertThat(providerOf(order)).isEqualTo(BANK);
    }

    // --- helpers --------------------------------------------------------------

    private CheckoutSessionResponse bank(Order order) {
        return order.getUser() == null
                ? paymentService.createGuestCheckoutSession(order.getId(), order.getOrderToken(), BANK, null)
                : paymentService.createCheckoutSession(order.getUser(), order.getId(), BANK, null);
    }

    private String providerOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getPaymentProvider();
    }

    private Order guestOrder(Product p) {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(p.getId(), 1));
        orderIds.add(order.getId());
        return order;
    }

    private User member() {
        User u = new User();
        u.setEmail("m-" + UUID.randomUUID() + "@example.com");
        u.setPassword("x");
        u.setName("Member");
        u.setRole(Role.USER);
        u = userRepository.save(u);
        userIds.add(u.getId());
        return u;
    }

    private Order memberOrder(User member, Product p) {
        CartItem item = new CartItem();
        item.setUser(member);
        item.setProduct(p);
        item.setQuantity(1);
        cartItemRepository.save(item);
        Order order = orderService.checkout(member);
        orderIds.add(order.getId());
        return order;
    }

    private Product product(int stock) {
        Product p = new Product();
        p.setName("テスト商品");
        p.setDescription("テスト商品");
        p.setPrice(new BigDecimal("1000"));
        p.setStock(stock);
        p.setTaxCategory(TaxCategory.STANDARD);
        p.setCategory(category);
        Product saved = productRepository.save(p);
        productIds.add(saved.getId());
        return saved;
    }
}
