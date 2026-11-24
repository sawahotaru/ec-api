package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.domain.User;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.repository.UserRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * お客さまの取り消しの口（会員・ゲスト）。実サーバーを立てて HTTP で確かめる
 * （認可・回数制限・応答の形はサービス層の呼び出しでは通らない）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000"
})
class OrderCancelWebTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired UserRepository userRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
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
        c.setName("ec09");
        c.setSlug("ec09-" + UUID.randomUUID());
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
        userIds.forEach(id -> {
            cartItemRepository.deleteAll(cartItemRepository.findByUserId(id));
            userRepository.deleteById(id);
        });
        productIds.forEach(productRepository::deleteById);
        categoryRepository.deleteById(category.getId());
        taxRateIds.forEach(taxRateRepository::deleteById);
    }

    @Test
    @DisplayName("ゲストの口: トークン違いは 404、トークン無しは 400、正しければ 200（連絡先もトークンも載らない）、2 回目は 409")
    void guestCancel() throws Exception {
        Order order = orderService.guestCheckout("secret-guest@example.com", Map.of(product.getId(), 1));
        orderIds.add(order.getId());
        String base = "/api/orders/guest/" + order.getId() + "/cancel";

        assertThat(send("POST", base + "?token=wrong-token", null).statusCode()).isEqualTo(404);
        assertThat(send("POST", base, null).statusCode()).isEqualTo(400);

        HttpResponse<String> ok = send("POST", base + "?token=" + order.getOrderToken(), null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"status\":\"CANCELLED\"");
        assertThat(ok.body()).doesNotContain(order.getOrderToken());
        assertThat(ok.body()).doesNotContain("secret-guest@example.com").doesNotContain("@");

        assertThat(send("POST", base + "?token=" + order.getOrderToken(), null).statusCode()).isEqualTo(409);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getReserved()).isZero();
    }

    @Test
    @DisplayName("会員の口: 他の会員の注文は 404、本人は 200（連絡先が載らない）")
    void memberCancel() throws Exception {
        User owner = member("owner-" + UUID.randomUUID() + "@example.com");
        User other = member("other-" + UUID.randomUUID() + "@example.com");
        CartItem item = new CartItem();
        item.setUser(owner);
        item.setProduct(product);
        item.setQuantity(1);
        cartItemRepository.save(item);
        Order order = orderService.checkout(owner);
        orderIds.add(order.getId());
        String path = "/api/orders/" + order.getId() + "/cancel";

        assertThat(send("POST", path, login(other)).statusCode()).isEqualTo(404);
        assertThat(send("POST", path, null).statusCode()).isIn(401, 403);

        HttpResponse<String> ok = send("POST", path, login(owner));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"status\":\"CANCELLED\"").doesNotContain("@");
        assertThat(productRepository.findById(product.getId()).orElseThrow().getReserved()).isZero();
    }

    private User member(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode("s3cret-member-pw"));
        u.setName("Member");
        u.setRole(Role.USER);
        u = userRepository.save(u);
        userIds.add(u.getId());
        return u;
    }

    private String login(User user) throws Exception {
        HttpResponse<String> res = send("POST", "/api/auth/login",
                "{\"email\":\"" + user.getEmail() + "\",\"password\":\"s3cret-member-pw\"}", null);
        assertThat(res.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String bearerOrBody) throws Exception {
        return send(method, path, null, bearerOrBody);
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
