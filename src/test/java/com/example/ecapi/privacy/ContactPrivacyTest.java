package com.example.ecapi.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.dto.OrderDtos.OrderResponse;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.service.OrderService;
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
 * 連絡先の扱い（伏せ字と保持期間）。
 *
 * <p>公開デモの実害リスクは管理画面そのものではなく、<strong>誰でもゲスト購入で他人の
 * メールアドレスを投入でき、それが恒久的に残る</strong>ことにある。対処は2段:
 * 表示時に伏せる（管理画面のみ）／一定期間で実体を消す。
 *
 * <p>消すのは<strong>連絡先だけで、注文は消さない</strong>。注文ごと消すと売上集計から
 * 過去が消え、「30日より前の売上が無かったことになる」という別の嘘を生む。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.demo.mask-contact=true",
        "app.demo.retention-days=30",
        // スイープはテストから明示的に呼ぶ（裏で走られると観測が不安定になる）
        "app.demo.retention-sweep-ms=3600000"
})
class ContactPrivacyTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired ContactRetentionScheduler retention;
    @Autowired JdbcTemplate jdbc;

    private Product matcha;

    @BeforeEach
    void reset() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        taxRateRepository.deleteAll();

        TaxRate rate = new TaxRate();
        rate.setCategory(TaxCategory.REDUCED);
        rate.setRatePercent(new BigDecimal("8.00"));
        rate.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        taxRateRepository.save(rate);

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

    @Test
    @DisplayName("マスクはローカル部だけを伏せ、ドメインは残す")
    void maskingKeepsTheDomain() {
        // 誰の注文か区別はつくが、連絡先としては使えない、が狙い
        assertThat(ContactMask.mask("guest@example.com")).isEqualTo("g***@example.com");
        assertThat(ContactMask.mask("a@example.com")).isEqualTo("***@example.com");
        assertThat(ContactMask.mask(null)).isNull();
        assertThat(ContactMask.mask("  ")).isNull();
        assertThat(ContactMask.maskForLog(null)).isEqualTo("-");
    }

    @Test
    @DisplayName("マスクした複製は、連絡先以外を変えない")
    void maskingTouchesOnlyTheContact() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 2));
        OrderResponse plain = OrderResponse.from(order);

        OrderResponse masked = plain.masked();

        assertThat(masked.userEmail()).isEqualTo("g***@example.com");
        assertThat(masked.id()).isEqualTo(plain.id());
        assertThat(masked.totalAmount()).isEqualByComparingTo(plain.totalAmount());
        assertThat(masked.status()).isEqualTo(plain.status());
        assertThat(masked.items()).hasSameSizeAs(plain.items());
    }

    @Test
    @DisplayName("本人の照会では伏せない（自分のアドレスが読めないのは不具合に見える）")
    void ownerLookupIsNotMasked() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));

        OrderResponse response = OrderResponse.from(
                orderService.getGuestOrder(order.getId(), order.getOrderToken()));

        assertThat(response.userEmail()).isEqualTo("guest@example.com");
    }

    @Test
    @DisplayName("保持期間を過ぎると連絡先とトークンが消え、金額と状態は残る")
    void retentionRemovesContactsOnly() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));
        orderService.markPaid(order.getId(), "stripe", "ref");
        Long id = order.getId();

        // ⚠ createdAt は updatable=false なので、setCreatedAt + save では**何も起きない**
        //    （エラーも出ずに黙って無視される）。日付を戻すには SQL を直接当てるしかない。
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)), id);

        retention.purgeOldContacts();

        Order after = orderRepository.findById(id).orElseThrow();
        assertThat(after.getGuestEmail()).isNull();
        assertThat(after.getOrderToken()).isNull();
        // ここが要点: 注文そのものは消えない（消すと売上集計から過去が消える）
        assertThat(after.getTotalAmount()).isEqualByComparingTo("1620");
        assertThat(after.getStatus().name()).isEqualTo("PAID");
        assertThat(after.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("保持期間内の注文には触らない")
    void recentOrdersAreLeftAlone() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(matcha.getId(), 1));

        retention.purgeOldContacts();

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getGuestEmail()).isEqualTo("guest@example.com");
        assertThat(after.getOrderToken()).isNotNull();
    }
}
