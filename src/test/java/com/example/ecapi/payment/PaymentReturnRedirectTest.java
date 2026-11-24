package com.example.ecapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 旧い戻り先（/api/payments/success・cancel）は 302 でストアへ転送するだけ。
 * 注文番号は数字 18 桁までのときだけ付け、それ以外は使わない。本文は返さない・DB は読まない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "server.servlet.context-path=/ec"
})
class PaymentReturnRedirectTest {

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** query は URL エンコード済みの文字列（空なら orderId なし）。expectedId は転送先に付く番号（空なら付かない）。 */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(value = {
            "success; orderId=12; 12",
            "cancel; orderId=12; 12",
            "success; ; ",
            "cancel; ; ",
            "success; orderId=abc; ",
            "cancel; orderId=abc; ",
            "success; orderId=0; ",
            "cancel; orderId=0; ",
            "success; orderId=%3Cscript%3Ealert(1)%3C%2Fscript%3E; ",
            "cancel; orderId=%3Cscript%3Ealert(1)%3C%2Fscript%3E; ",
            "success; orderId=1234567890123456789; ",
            "cancel; orderId=1234567890123456789; "
    }, delimiter = ';')
    void redirectsToStore(String page, String query, String expectedId) throws Exception {
        String q = query == null || query.isBlank() ? "" : "?" + query.trim();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ec/api/payments/" + page + q))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        String expected = "/ec/?payment=" + page
                + (expectedId == null || expectedId.isBlank() ? "" : "&orderId=" + expectedId.trim());
        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location")).hasValue(expected);
        assertThat(res.body()).doesNotContain("<");
        assertThat(res.headers().firstValue("Server")).isEmpty();
        assertThat(res.headers().firstValue("X-Powered-By")).isEmpty();
    }
}
