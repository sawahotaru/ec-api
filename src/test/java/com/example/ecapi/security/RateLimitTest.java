package com.example.ecapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.UserRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 送信元ごとの回数制限。
 *
 * <p>監査で見つかった壊し方はどれも<strong>認証も不正入力も要らない</strong>:
 * {@code POST /api/orders/guest-checkout} を未認証で撃てば在庫を引き当てられ、
 * 同じ口から行が増え、{@code POST /api/auth/register} から users が増える。
 * 既存の {@code LoginThrottle} は<em>失敗</em>しか数えないので、全部成功する
 * この手口を1件も止めなかった。
 *
 * <p>したがってここで固定するのは「429 が返ること」ではなく、
 * <strong>断られた要求が1つも資源を消費していないこと</strong>（在庫が増えて握られない、
 * 行が増えない）と、<strong>抜け道が無いこと</strong>（ログインしても枠が消えない、
 * 窓口を変えても同じ枠に乗る）。
 *
 * <p>実サーバーを立てて HTTP で叩く。判定はインターセプタなので、サービス層を直接呼ぶ
 * テストではそもそも通らない。クライアントは JDK 標準の {@link HttpClient}
 * （Boot 4 で {@code MockMvc} の自動設定も {@code TestRestTemplate} も別モジュールへ移った）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.order.purge-sweep-ms=3600000",
        // 実際の既定値（10 / 5 / 120 / 60）で試すと1テストに百数十回の往復が要る。
        // 「上限で止まる」ことの確認に必要なのは比較であって絶対値ではないので小さくする。
        "app.rate-limit.checkout=3",
        "app.rate-limit.register=2",
        "app.rate-limit.quote=4",
        "app.rate-limit.default=5",
        "app.rate-limit.window-seconds=600",
        "app.rate-limit.register-window-seconds=600"
})
class RateLimitTest {

    @Autowired RequestRateLimiter limiter;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Product matcha;

    @BeforeEach
    void reset() {
        // 計数器はプロセスに1つ。テスト間で持ち越すと、落ちたテストが次のテストを落とす。
        limiter.clear();
        orderRepository.deleteAll();
        // H2 はインメモリでもURLが同じ＝**全テストクラスで1つのDB**。カート行を残したまま
        // 終わると、次に products を消そうとしたクラスが外部キーで落ちる（＝無関係な
        // テストが落ちる）。入口でも出口でも消しておく。
        cartItemRepository.deleteAll();
        userRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();

        Category tea = new Category();
        tea.setName("日本茶");
        tea.setSlug("tea");
        tea = categoryRepository.save(tea);

        Product p = new Product();
        p.setName("宇治抹茶 30g");
        p.setDescription("テスト用");
        p.setPrice(new BigDecimal("1620"));
        p.setStock(1000);           // 在庫側の上限ではなく回数側の上限で止まることを見たい
        p.setCategory(tea);
        p.setTaxCategory(TaxCategory.REDUCED);
        matcha = productRepository.save(p);
    }

    @Test
    @DisplayName("ゲスト注文は上限で止まり、断られた分は在庫を1つも握っていない")
    void guestCheckoutStopsAtTheLimitAndHoldsNothingExtra() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(guestCheckout(1).statusCode()).as("attempt %d", i + 1).isEqualTo(201);
        }

        HttpResponse<String> blocked = guestCheckout(1);
        assertThat(blocked.statusCode()).isEqualTo(429);
        // いつ戻ってよいかを機械にも伝える。無いと、まともなクライアントほど即再試行して窓を延ばす
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();

        // 本題。429 が返っていても在庫が握られていたら、制限は何も守っていない
        assertThat(reserved()).isEqualTo(3);
        assertThat(orderRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("ログインしても枠は消えない（登録1回で制限を外せない）")
    void authenticatingDoesNotBuyAnExemption() throws Exception {
        String token = register("member@example.com");

        // カートが空なので 400 になるが、数えるのは成否ではなく回数
        for (int i = 0; i < 3; i++) {
            assertThat(send("POST", "/api/orders/checkout", "{}", token).statusCode())
                    .as("attempt %d", i + 1).isNotEqualTo(429);
        }
        assertThat(send("POST", "/api/orders/checkout", "{}", token).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("会員登録は上限で止まり、それ以上 users が増えない")
    void registrationIsBounded() throws Exception {
        assertThat(send("POST", "/api/auth/register", registerBody("a@example.com"), null)
                .statusCode()).isEqualTo(201);
        assertThat(send("POST", "/api/auth/register", registerBody("b@example.com"), null)
                .statusCode()).isEqualTo(201);

        assertThat(send("POST", "/api/auth/register", registerBody("c@example.com"), null)
                .statusCode()).isEqualTo(429);
        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("見積もりは注文より枠が広い（カートを触るたびに呼ばれるので止めてはいけない）")
    void quotesGetTheirOwnLargerBudget() throws Exception {
        // 注文の枠（3）を超える回数を見積もりで叩いても通る＝枠が混ざっていない
        for (int i = 0; i < 4; i++) {
            assertThat(quote().statusCode()).as("quote %d", i + 1).isNotEqualTo(429);
        }
        assertThat(quote().statusCode()).isEqualTo(429);

        // 見積もりを使い切っても、注文の枠は手つかず
        assertThat(guestCheckout(1).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("閲覧（GET）は数えない。デモが見られなくなるほうが損失が大きい")
    void readsAreNeverRateLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(send("GET", "/api/products", null, null).statusCode())
                    .as("read %d", i + 1).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("決済Webhookは除外（事業者からの正当な未認証要求を 429 で落とさない）")
    void paymentWebhooksAreExempt() throws Exception {
        // Stripe 無効なので内容は通らないが、**429 にはならない**ことが要点。
        // ここで落とすと、落ちたことは「注文が PENDING のままになる」形でしか現れない。
        for (int i = 0; i < 12; i++) {
            assertThat(send("POST", "/api/payments/stripe/webhook", "{}", null).statusCode())
                    .as("webhook %d", i + 1).isNotEqualTo(429);
        }
    }

    @Test
    @DisplayName("表に無い書き込みも既定の枠に入る（新しいAPIを足して書き忘れても無制限にならない）")
    void unlistedWritesFallBackToTheDefaultBucket() throws Exception {
        // /api/cart は回数制限の表に載っていない。載っていないものが素通りするなら、
        // 表を持つこと自体が穴になる。
        String token = register("cart@example.com");
        String body = "{\"productId\":" + matcha.getId() + ",\"quantity\":1}";

        boolean blocked = false;
        for (int i = 0; i < 10 && !blocked; i++) {
            blocked = send("POST", "/api/cart/items", body, token).statusCode() == 429;
        }
        assertThat(blocked).as("既定の枠が効いていない").isTrue();
    }

    @AfterEach
    void cleanUpSharedDatabase() {
        cartItemRepository.deleteAll();
    }

    /* ---------- helpers ---------- */

    private int reserved() {
        return productRepository.findById(matcha.getId()).orElseThrow().getReserved();
    }

    private HttpResponse<String> guestCheckout(int quantity) throws Exception {
        return send("POST", "/api/orders/guest-checkout",
                "{\"email\":\"guest@example.com\",\"items\":[{\"productId\":" + matcha.getId()
                        + ",\"quantity\":" + quantity + "}]}", null);
    }

    private HttpResponse<String> quote() throws Exception {
        return send("POST", "/api/checkout/quote",
                "{\"items\":[{\"productId\":" + matcha.getId() + ",\"quantity\":1}]}", null);
    }

    private String registerBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"s3cret-pw\",\"name\":\"Tester\"}";
    }

    private String register(String email) throws Exception {
        HttpResponse<String> response = send("POST", "/api/auth/register", registerBody(email), null);
        assertThat(response.statusCode()).isEqualTo(201);
        return extract(response.body(), "token");
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, payload);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key) + key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
