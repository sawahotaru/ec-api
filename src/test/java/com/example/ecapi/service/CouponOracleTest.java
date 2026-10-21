package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.CouponRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.security.ClientKey;
import com.example.ecapi.security.CouponAttemptThrottle;
import com.example.ecapi.security.RequestRateLimiter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * クーポンコードの存在オラクル。
 *
 * <p>{@code POST /api/checkout/quote} は未認証で叩けて、使えない理由を<strong>種類別に</strong>
 * 返す（見つかりません／期限切れ／上限到達／最低金額◯円以上）。これは意図した親切さで、
 * <strong>変えない</strong>——「使えません」とだけ言われた客は、1品足せば使えたことに気付かずに
 * カートを捨てる。問題は親切さではなく、<strong>無制限に引けること</strong>だった。
 * 応答の違いを数千回引けば、存在するコードが列挙できる。
 *
 * <p>したがってここで固定するのは「理由が消えていないこと」と「回数で止まること」の両方。
 * 片方だけでは直したことにならない（理由を消せば客が困り、回数を止めなければ列挙が通る）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.order.purge-sweep-ms=3600000",
        "app.coupon.max-attempts=4",
        "app.coupon.window-seconds=900",
        "app.coupon.lockout-seconds=900"
})
class CouponOracleTest {

    @Autowired CouponAttemptThrottle throttle;
    @Autowired RequestRateLimiter rateLimiter;
    @Autowired CouponRepository couponRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Product matcha;

    @BeforeEach
    void reset() {
        throttle.reset("ip:127.0.0.1");
        throttle.reset(ClientKey.INTERNAL);
        rateLimiter.clear();
        couponRepository.deleteAll();
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
        p.setStock(100);
        p.setCategory(saved);
        p.setTaxCategory(TaxCategory.REDUCED);
        matcha = productRepository.save(p);

        Coupon coupon = new Coupon();
        coupon.setCode("WELCOME500");
        coupon.setDiscountType(DiscountType.FIXED);
        coupon.setValue(new BigDecimal("500"));
        coupon.setEnabled(true);
        couponRepository.save(coupon);
    }

    @Test
    @DisplayName("当てずっぽうは回数で止まる。止まった後は存在するコードも同じ応答になる")
    void guessingStopsAndTheAnswerStopsBeingInformative() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(quote("NOPE" + i).statusCode()).as("guess %d", i + 1).isEqualTo(400);
        }

        assertThat(quote("NOPE-ONE-MORE").statusCode()).isEqualTo(429);

        // 肝心なのはここ。締めた後は**実在するコード**を出しても同じ 429 になる＝
        // 応答の違いから存在を読み取れない（締め方が甘いと、ここだけ 200 が返って
        // 「止まっているように見えて、実は一番知られたくない1ビットだけ漏れ続ける」）。
        assertThat(quote("WELCOME500").statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("理由は今も種類別に返る（買い手に伝わらなくなっては直したことにならない）")
    void reasonsAreStillSpelledOut() throws Exception {
        assertThat(quote("NO-SUCH-CODE").body()).contains("見つかりません");

        Coupon expired = new Coupon();
        expired.setCode("OLD2020");
        expired.setDiscountType(DiscountType.FIXED);
        expired.setValue(new BigDecimal("100"));
        expired.setEnabled(true);
        expired.setValidTo(LocalDate.of(2020, 1, 1));
        couponRepository.save(expired);

        assertThat(quote("OLD2020").body()).contains("有効期限");
    }

    @Test
    @DisplayName("正しいコードを入れたら忘れる（買い物を続ける客が自分で詰まらない）")
    void successClearsTheCount() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(quote("WRONG" + i).statusCode()).isEqualTo(400);
        }
        assertThat(quote("WELCOME500").statusCode()).isEqualTo(200);

        // リセットされていなければ、ここが 429 になる
        for (int i = 0; i < 3; i++) {
            assertThat(quote("WRONG-AGAIN" + i).statusCode()).as("after reset %d", i + 1)
                    .isEqualTo(400);
        }
    }

    /* ---------- helpers ---------- */

    private HttpResponse<String> quote(String couponCode) throws IOException, InterruptedException {
        String body = "{\"items\":[{\"productId\":" + matcha.getId() + ",\"quantity\":1}],"
                + "\"couponCode\":\"" + couponCode + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/checkout/quote"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
