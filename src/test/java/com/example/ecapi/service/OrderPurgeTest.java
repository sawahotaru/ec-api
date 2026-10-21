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
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 支払われずに終わった古い注文の削除。
 *
 * <p>注文は<strong>未認証で何件でも作れる</strong>のに、これまで1行も消えなかった。
 * {@code ContactRetentionScheduler} が消すのは連絡先（{@code guestEmail} / {@code orderToken}）
 * だけで、行は残る——小さなVMの PostgreSQL に対するディスク枯渇の経路だった。
 *
 * <p>ただし<strong>何を消してよいかは自明ではない</strong>。売上集計は「支払い済みだけ」を
 * 数えるので、PAID を消すと「◯日より前の売上が無かったことになる」という別の嘘を生む
 * （連絡先だけを消す設計が選ばれたのも同じ理由による）。消してよいのは集計に1円も入らない
 * EXPIRED / CANCELLED だけで、それをここで固定する。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.order.purge-sweep-ms=3600000"
})
class OrderPurgeTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired JdbcTemplate jdbc;

    private Product matcha;

    @BeforeEach
    void reset() {
        orderRepository.deleteAll();
        // H2 はインメモリでもURLが同じ＝**全テストクラスで1つのDB**。他のクラスが残した
        // カート行が products の外部キーを握っていると、ここの deleteAll がそれだけで落ちる。
        cartItemRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        taxRateRepository.deleteAll();

        TaxRate rate = new TaxRate();
        rate.setCategory(TaxCategory.REDUCED);
        rate.setRatePercent(new BigDecimal("8"));
        rate.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        taxRateRepository.save(rate);

        Category category = new Category();
        category.setName("日本茶");
        category.setSlug("tea");
        Category saved = categoryRepository.save(category);

        Product p = new Product();
        p.setName("宇治抹茶 30g");
        p.setDescription("テスト用");
        p.setPrice(new BigDecimal("1620"));
        p.setStock(1000);
        p.setCategory(saved);
        p.setTaxCategory(TaxCategory.REDUCED);
        matcha = productRepository.save(p);
    }

    @Test
    @DisplayName("支払われずに終わった古い注文は明細ごと消える")
    void abandonedOrdersAreRemovedWithTheirLines() {
        Order expired = age(place(), OrderStatus.EXPIRED, 40);
        Order cancelled = age(place(), OrderStatus.CANCELLED, 40);

        assertThat(orderService.purgeAbandonedOrders(cutoff(30), 500)).isEqualTo(2);

        assertThat(orderRepository.findById(expired.getId())).isEmpty();
        assertThat(orderRepository.findById(cancelled.getId())).isEmpty();
    }

    @Test
    @DisplayName("支払い済みは何年経っても消さない（売上集計から過去が消えるため）")
    void paidOrdersAreNeverPurged() {
        Order paid = place();
        orderService.markPaid(paid.getId(), "bank_transfer", "REF-1");
        age(paid, OrderStatus.PAID, 3650);

        assertThat(orderService.purgeAbandonedOrders(cutoff(30), 500)).isZero();
        assertThat(orderRepository.findById(paid.getId())).isPresent();
    }

    @Test
    @DisplayName("まだ新しい注文は消さない（保持期間を過ぎたものだけ）")
    void recentOrdersSurvive() {
        Order recent = age(place(), OrderStatus.EXPIRED, 1);

        assertThat(orderService.purgeAbandonedOrders(cutoff(30), 500)).isZero();
        assertThat(orderRepository.findById(recent.getId())).isPresent();
    }

    @Test
    @DisplayName("1回で消す件数には上限がある（溜まった環境で1トランザクションが巨大にならない）")
    void purgeIsBatched() {
        for (int i = 0; i < 5; i++) {
            age(place(), OrderStatus.EXPIRED, 40);
        }

        assertThat(orderService.purgeAbandonedOrders(cutoff(30), 2)).isEqualTo(2);
        assertThat(orderRepository.count()).isEqualTo(3);
    }

    /* ---------- helpers ---------- */

    private Order place() {
        return orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));
    }

    /**
     * 古い注文に見せかける。
     *
     * <p>⚠ {@code createdAt} は {@code @Column(updatable = false)} なので、エンティティ経由で
     * 書き換えても<strong>黙って無視される</strong>（それが正しい——注文日は後から動かない）。
     * テストのためだけに可変にするのは本末転倒なので、SQL で直接書く。
     */
    private Order age(Order order, OrderStatus status, int days) {
        jdbc.update("UPDATE orders SET status = ?, created_at = ? WHERE id = ?",
                status.name(), Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), order.getId());
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private static Instant cutoff(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }
}
