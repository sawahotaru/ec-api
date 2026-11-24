package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

/** 期限切れの掃除は、カードは保留の分数、銀行振込を選んだ注文は振込の日数で切る。 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000"
})
class OrderExpiryByMethodTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> taxRateIds = new ArrayList<>();
    private Category category;
    private Product product;

    @BeforeEach
    void setUp() {
        TaxRate r = new TaxRate();
        r.setCategory(TaxCategory.STANDARD);
        r.setRatePercent(new BigDecimal("10.00"));
        r.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        taxRateIds.add(taxRateRepository.save(r).getId());

        Category c = new Category();
        c.setName("ec10x");
        c.setSlug("ec10x-" + UUID.randomUUID());
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
    }

    @AfterEach
    void restore() {
        orderIds.forEach(orderRepository::deleteById);
        productIds.forEach(productRepository::deleteById);
        categoryRepository.deleteById(category.getId());
        taxRateIds.forEach(taxRateRepository::deleteById);
    }

    @Test
    @DisplayName("カードの注文だけが先に期限切れになり、振込の注文は振込の期限まで残る。引当は注文ごとに戻る")
    void bankTransferOrdersOutliveCardOrders() {
        Order card = order("stripe");
        Order bank = order("bank_transfer");
        Order unchosen = order(null);   // 支払い方法を選んでいない注文はカードと同じ
        assertThat(reserved()).isEqualTo(3);

        Instant now = Instant.now();
        orderService.expireStalePendingOrders(now.plusSeconds(1), now.minus(Duration.ofDays(1)));

        assertThat(status(card)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(status(unchosen)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(status(bank)).isEqualTo(OrderStatus.PENDING);
        assertThat(reserved()).isEqualTo(1);

        orderService.expireStalePendingOrders(now.plusSeconds(1), now.plusSeconds(1));

        assertThat(status(bank)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reserved()).isZero();
    }

    @Test
    @DisplayName("1 引数の版は決済手段を区別せず、期限より前の PENDING を全部期限切れにする")
    void oneArgumentSweepIgnoresPaymentMethod() {
        Order card = order("stripe");
        Order bank = order("bank_transfer");

        orderService.expireStalePendingOrders(Instant.now().plusSeconds(1));

        assertThat(status(card)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(status(bank)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reserved()).isZero();
    }

    private Order order(String provider) {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(product.getId(), 1));
        orderIds.add(order.getId());
        if (provider != null) {
            Order stored = orderRepository.findById(order.getId()).orElseThrow();
            stored.setPaymentProvider(provider);
            orderRepository.save(stored);
        }
        return order;
    }

    private OrderStatus status(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private int reserved() {
        return productRepository.findById(product.getId()).orElseThrow().getReserved();
    }
}
