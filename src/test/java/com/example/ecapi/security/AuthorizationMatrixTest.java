package com.example.ecapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.User;
import com.example.ecapi.repository.UserRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 誰が何を叩けるか。
 *
 * <p>監査で「<strong>認可を検証するテストが1つも無い</strong>」と指摘された
 * （{@code isUnauthorized|isForbidden|ROLE_ADMIN|hasRole} の全文検索が 0 件）。
 * 認可は {@code SecurityConfig} の1メソッドに集まっていて<strong>1行で全部外れる</strong>——
 * そして外れても<strong>画面は完全に正常に動く</strong>。管理画面を「隠さず壊せなくする」
 * 方針を採った以上、権限の境界だけは機械で押さえておく必要がある。
 *
 * <p>区別して確かめること:
 * <ul>
 *   <li>未認証は <b>401</b>（「ログインすれば入れるかもしれない」）</li>
 *   <li>USER ロールは <b>403</b>（「ログインし直しても入れない」）</li>
 *   <li>ADMIN は <b>200</b>（塞いだつもりが全員を塞いでいた、を検出する）</li>
 * </ul>
 * 401 と 403 が入れ替わっていると、攻撃者にはアカウントの有無が、利用者には
 * 「もう一度ログインすれば直る」という誤った案内が伝わる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.order.purge-sweep-ms=3600000"
})
class AuthorizationMatrixTest {

    /** 管理APIの代表。読み取り（GET）だけを使う——読めるかどうかが権限の境界そのもの。 */
    private static final String[] ADMIN_READS = {
            "/api/admin/stats", "/api/admin/orders", "/api/admin/coupons",
            "/api/admin/settings", "/api/admin/tax-rates"
    };

    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RequestRateLimiter rateLimiter;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        userRepository.deleteAll();
        adminToken = tokenFor("admin@test.local", Role.ADMIN);
        userToken = tokenFor("user@test.local", Role.USER);
    }

    @Test
    @DisplayName("未認証では管理APIに触れない（401）")
    void anonymousCannotReachAdmin() throws Exception {
        for (String path : ADMIN_READS) {
            assertThat(get(path, null).statusCode()).as("GET %s", path).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("USER ロールでは管理APIに触れない（403: ログインし直しても入れない）")
    void plainUsersAreForbiddenFromAdmin() throws Exception {
        for (String path : ADMIN_READS) {
            assertThat(get(path, userToken).statusCode()).as("GET %s", path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("ADMIN は読める（塞いだつもりが全員を塞いでいた、を検出する）")
    void adminsCanRead() throws Exception {
        for (String path : ADMIN_READS) {
            assertThat(get(path, adminToken).statusCode()).as("GET %s", path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("USER のトークンに管理APIへの書き込みもできない（403）")
    void plainUsersCannotWriteToAdmin() throws Exception {
        assertThat(send("POST", "/api/admin/coupons",
                "{\"code\":\"X\",\"discountType\":\"FIXED\",\"value\":1}", userToken).statusCode())
                .isEqualTo(403);
        assertThat(send("DELETE", "/api/admin/products/1", null, userToken).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("会員向けAPIも未認証では通らない（自分の注文・カート）")
    void memberEndpointsRequireLogin() throws Exception {
        assertThat(get("/api/orders", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/cart", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("壊れた／偽造されたトークンは通らない（署名検証が効いている）")
    void forgedTokensAreRejected() throws Exception {
        // 鍵を知らずに作れるのはここまで。通ってしまうなら署名を見ていない
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJzdWIiOiJhZG1pbkB0ZXN0LmxvY2FsIiwicm9sZSI6IkFETUlOIn0.not-a-real-signature";
        assertThat(get("/api/admin/stats", forged).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("公開カタログは未認証で読める（締めすぎて店が見えなくなっていないこと）")
    void thePublicCatalogueStaysPublic() throws Exception {
        assertThat(get("/api/products", null).statusCode()).isEqualTo(200);
        assertThat(get("/api/categories", null).statusCode()).isEqualTo(200);
        assertThat(get("/api/store/branding", null).statusCode()).isEqualTo(200);
    }

    /* ---------- helpers ---------- */

    private String tokenFor(String email, Role role) throws Exception {
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("s3cret-pw-123"));
        user.setName(role.name());
        user.setRole(role);
        userRepository.save(user);

        HttpResponse<String> login = send("POST", "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"s3cret-pw-123\"}", null);
        assertThat(login.statusCode()).isEqualTo(200);
        return extract(login.body(), "token");
    }

    private HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        return send("GET", path, null, bearer);
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
