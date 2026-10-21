package com.example.ecapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.User;
import com.example.ecapi.repository.UserRepository;
import com.example.ecapi.security.RequestRateLimiter;
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
 * {@code ?sort=} に存在しない項目を渡したときの応答。
 *
 * <p>以前は <strong>500</strong> だった（本番 {@code /ec/} で実測）。Spring Data の
 * {@code PropertyReferenceException} が {@link GlobalExceptionHandler} に無く、素通りしていた。
 *
 * <p>何が問題か:
 * <ul>
 *   <li>{@code Pageable} を取るエンドポイントは<strong>未認証で叩けるものを含む</strong>。
 *       つまり誰でも 500 を起こせて、そのたびにサーバー側にフルスタックが積まれる。
 *       回数制限の無い GET と組み合わさると、ただのログ増幅器になる。</li>
 *   <li>500 は「サーバーが壊れている」と読める。入力の間違いを壊れたと表示する展示物は、
 *       それ自体が減点になる。</li>
 * </ul>
 *
 * <p>直し方は<strong>ハンドラ1本</strong>で、並び替え項目の許可リストではない。
 * 許可リストは {@code Pageable} を取るAPIを足すたびに書き足す必要があり、書き忘れが
 * そのまま 500 に戻る。ここで列挙しているのは「{@code Pageable} を取る全エンドポイント」で、
 * 1つでも漏れていたらそこだけ 500 に戻っていることを検出する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.order.purge-sweep-ms=3600000"
})
class PageableSortTest {

    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RequestRateLimiter rateLimiter;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        userRepository.deleteAll();

        User admin = new User();
        admin.setEmail("admin@test.local");
        admin.setPassword(passwordEncoder.encode("s3cret-admin-pw"));
        admin.setName("Admin");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        HttpResponse<String> login = post("/api/auth/login",
                "{\"email\":\"admin@test.local\",\"password\":\"s3cret-admin-pw\"}");
        adminToken = extract(login.body(), "token");
    }

    @Test
    @DisplayName("存在しない並び替え項目は 400。Pageable を取るどのエンドポイントでも")
    void everyPageableEndpointAnswers400() throws Exception {
        assertThat(get("/api/products?sort=bogus", null).statusCode()).isEqualTo(400);
        assertThat(get("/api/orders?sort=bogus", adminToken).statusCode()).isEqualTo(400);
        assertThat(get("/api/admin/orders?sort=bogus", adminToken).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("応答に内部の型名を載せない（例外の既定メッセージはエンティティ名まで含む）")
    void theMessageDoesNotLeakTheDomainModel() throws Exception {
        String body = get("/api/products?sort=bogus", null).body();

        assertThat(body).contains("bogus");                     // 何を直せばよいかは分かる
        assertThat(body).doesNotContain("Product");             // 内部のクラス名は教えない
        assertThat(body).doesNotContain("com.example.ecapi");
    }

    @Test
    @DisplayName("正しい並び替えは今までどおり通る（ハンドラが本物の機能を潰していない）")
    void validSortsStillWork() throws Exception {
        assertThat(get("/api/products?sort=price,asc", null).statusCode()).isEqualTo(200);
    }

    /* ---------- helpers ---------- */

    private HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key) + key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
