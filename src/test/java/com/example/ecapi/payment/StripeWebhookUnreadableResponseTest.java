package com.example.ecapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 復元できない通知には 500 と固定文を返す（決済側に再送させる）。本文に例外名・製品名・秘密の語を出さない。
 * 架空のキーで Stripe の実装を有効にするが、外部への要求は発生しない（Webhook の受信だけ）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.stripe.secret-key=sk_test_unit_dummy",
        "app.stripe.webhook-secret=" + StripeWebhookPayloadTest.SECRET
})
class StripeWebhookUnreadableResponseTest {

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void unreadablePayloadReturns500WithFixedMessage() throws Exception {
        String payload = StripeWebhookPayloadTest.event(
                "\"amount_total\":\"abc\",\"metadata\":{\"orderId\":\"42\"}");
        String sig = StripeWebhookPayloadTest.sign(payload, Instant.now().getEpochSecond());

        HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/payments/stripe/webhook"))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", sig)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).contains("処理できませんでした");
        assertThat(res.body()).doesNotContain("Exception", "Stripe", "whsec", "stripe");
    }
}
