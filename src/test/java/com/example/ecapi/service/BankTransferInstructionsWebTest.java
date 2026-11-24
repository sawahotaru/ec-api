package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 振込の案内は、決済ページへ移さず JSON で返す（`/api/` の下に HTML を置かない）。実サーバーを立てて HTTP で確かめる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.payment.bank-transfer.enabled=true"
})
class BankTransferInstructionsWebTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        c.setName("ec10w");
        c.setSlug("ec10w-" + UUID.randomUUID());
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
    @DisplayName("振込を選ぶと、ストアのトップを指す redirectUrl と、JSON の案内が返る")
    void selectingBankTransferReturnsJsonInstructions() throws Exception {
        Order order = place();
        String reference = "BT-%06d".formatted(order.getId());

        HttpResponse<String> select = send("POST", "/api/payments/guest/orders/" + order.getId()
                + "/checkout-session?provider=bank_transfer&token=" + order.getOrderToken());

        assertThat(select.statusCode()).isEqualTo(200);
        assertThat(select.body()).contains("\"redirectUrl\":\"/\"");   // 決済ページへは移さない（context-path なし＋/）
        assertThat(select.body()).contains("\"instructions\"").contains("サンプルストア")
                .contains(reference).contains("お支払い期限").contains(String.valueOf(order.getId()));

        HttpResponse<String> again = send("GET", "/api/payments/bank_transfer/instructions?orderId="
                + order.getId() + "&token=" + order.getOrderToken());
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(again.body()).contains("サンプルストア").contains(reference).contains("お支払い期限");
        HttpResponse<String> mine = send("GET", "/api/orders/guest/" + order.getId() + "?token=" + order.getOrderToken());
        assertThat(mine.body()).contains("\"transferInstructions\":true").doesNotContain("bank_transfer");
        assertThat(again.body()).doesNotContain("<table").doesNotContain("<h1").doesNotContain("<!doctype");
    }

    @Test
    @DisplayName("振込を選んでいない注文の案内は 404")
    void instructionsAreNotAvailableUntilBankTransferIsChosen() throws Exception {
        Order order = place();

        HttpResponse<String> res = send("GET", "/api/payments/bank_transfer/instructions?orderId="
                + order.getId() + "&token=" + order.getOrderToken());

        assertThat(res.statusCode()).isEqualTo(404);
    }

    private Order place() {
        Order order = orderService.guestCheckout("guest@example.com", Map.of(product.getId(), 1));
        orderIds.add(order.getId());
        return order;
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        b.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
