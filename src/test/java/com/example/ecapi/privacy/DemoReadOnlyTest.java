package com.example.ecapi.privacy;

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
 * 読み取り専用デモ。
 *
 * <p>この管理画面は<strong>隠さない</strong>方針を採った——税率の有効期間管理・送料・クーポン・
 * 売上集計は、そこにしか無い展示物だから。かわりに「入られても壊せない」状態を作る。
 * したがって守るべきは<strong>書き込みが1つ残らず止まること</strong>で、これは
 * <strong>設定が効いていなくても画面は完全に正常に見える</strong>種類の性質なので、
 * テスト以外に気付く手段が無い。
 *
 * <p>実サーバーを立てて HTTP で叩く。判定は Spring MVC のインターセプタなので、
 * サービス層を直接呼ぶテストでは<strong>そもそも通らない</strong>
 * （同じ日に `/api/checkout/**` の認可漏れを実物で踏んだのと同じ構図）。
 *
 * <p>クライアントは JDK 標準の {@link HttpClient}。Spring のテスト用クライアントは
 * バージョン間でモジュールが動く（Boot 4 で `TestRestTemplate` も `MockMvc` の
 * 自動設定も別モジュールへ移った）が、こちらは動かない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.demo.read-only=true"
})
class DemoReadOnlyTest {

    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        userRepository.deleteAll();
        User admin = new User();
        admin.setEmail("admin@test.local");
        admin.setPassword(passwordEncoder.encode("s3cret-admin-pw"));
        admin.setName("Admin");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        HttpResponse<String> login = send("POST", "/api/auth/login",
                "{\"email\":\"admin@test.local\",\"password\":\"s3cret-admin-pw\"}", null);
        assertThat(login.statusCode()).isEqualTo(200);
        token = extract(login.body(), "token");
    }

    @Test
    @DisplayName("管理APIの参照は通る（見せるための画面なので、読めなければ意味が無い）")
    void adminReadsStillWork() throws Exception {
        for (String path : new String[]{"/api/admin/stats", "/api/admin/coupons", "/api/admin/settings",
                "/api/admin/orders", "/api/admin/tax-rates"}) {
            assertThat(asAdmin("GET", path, null).statusCode()).as("GET %s", path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("管理APIの書き込みは、メソッドを問わず 403")
    void everyAdminWriteIsRejected() throws Exception {
        // POST / PUT / PATCH / DELETE を別々のエンドポイントで確認する。
        // 「塞ぐ先を一覧で持つ」実装だと、このどれかが素通りする。
        assertThat(asAdmin("POST", "/api/admin/coupons",
                "{\"code\":\"X\",\"discountType\":\"FIXED\",\"value\":1}").statusCode()).isEqualTo(403);
        assertThat(asAdmin("PUT", "/api/admin/settings/shipping",
                "{\"fee\":0,\"freeThreshold\":0}").statusCode()).isEqualTo(403);
        assertThat(asAdmin("PATCH", "/api/admin/orders/1/status",
                "{\"status\":\"PAID\"}").statusCode()).isEqualTo(403);
        assertThat(asAdmin("DELETE", "/api/admin/products/1", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("画像アップロードも止まる（サーバーへのファイル書き込みが最も強い権限）")
    void uploadIsRejected() throws Exception {
        // ⚠ 本物の multipart で送る必要がある。Content-Type が合わないと**ハンドラに紐づかず**、
        //    インターセプタが走る前に 415 で返る（＝書き込みは起きないが 403 にもならない）。
        //    それでは「門番が効いている」ことの証明にならない。
        String boundary = "----ecapiTestBoundary";
        String crlf = "\r\n";
        String body = String.join(crlf,
                "--" + boundary,
                "Content-Disposition: form-data; name=\"file\"; filename=\"a.png\"",
                "Content-Type: image/png",
                "",
                "PNG",
                "--" + boundary + "--",
                "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/products/1/image"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("拒否の理由が本文に出る（ログインし直しても直らないと分かる）")
    void rejectionExplainsItself() throws Exception {
        assertThat(asAdmin("DELETE", "/api/admin/products/1", null).body()).contains("公開デモ");
    }

    @Test
    @DisplayName("買い物側は止めない（止めたらデモとして意味が無い）")
    void shoppingIsNotBlocked() throws Exception {
        HttpResponse<String> quote = send("POST", "/api/checkout/quote",
                "{\"items\":[{\"productId\":1,\"quantity\":1}]}", null);

        // 商品が無いので 404 になるが、**403 ではない** ＝ 門番に引っかかっていない
        assertThat(quote.statusCode()).isNotEqualTo(403);
    }

    /* ---------- helpers ---------- */

    private HttpResponse<String> asAdmin(String method, String path, String body) throws Exception {
        return send(method, path, body, token);
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

    /** JSON から1つの文字列フィールドを取り出すだけ（パーサを持ち込むまでもない）。 */
    private static String extract(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key) + key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
