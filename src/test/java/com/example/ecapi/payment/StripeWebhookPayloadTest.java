package com.example.ecapi.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.PaymentCallbackUnreadableException;
import com.example.ecapi.payment.provider.StripePaymentProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Webhook の本文の取り出しと、復元できないときの扱い。署名は架空の whsec でテスト内で作る。
 */
class StripeWebhookPayloadTest {

    static final String SECRET = "whsec_unit_test_dummy";

    private StripePaymentProvider provider() {
        StripeProperties props = new StripeProperties();
        props.setWebhookSecret(SECRET);
        return new StripePaymentProvider(props, new PaymentProperties());
    }

    static String sign(String payload, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return "t=" + timestamp + ",v1=" + hex;
    }

    static String event(String sessionFields) {
        return "{\"id\":\"evt_test_1\",\"object\":\"event\",\"api_version\":\"2020-08-27\",\"type\":\"checkout.session.completed\","
                + "\"livemode\":false,\"data\":{\"object\":{\"id\":\"cs_test_1\","
                + "\"object\":\"checkout.session\"," + sessionFields + "}}}";
    }

    static final String GOOD = event("\"amount_total\":5990,\"currency\":\"jpy\","
            + "\"payment_status\":\"paid\",\"metadata\":{\"orderId\":\"42\"}");

    @Test
    void validEventYieldsAmountCurrencyPaidAndEventId() throws Exception {
        long now = Instant.now().getEpochSecond();
        PaymentCallback cb = provider()
                .handleCallback(GOOD, Map.of("Stripe-Signature", sign(GOOD, now))).orElseThrow();

        assertThat(cb.orderId()).isEqualTo(42L);
        assertThat(cb.reference()).isEqualTo("cs_test_1");
        assertThat(cb.amountMinor()).isEqualTo(5990L);
        assertThat(cb.currency()).isEqualTo("jpy");
        assertThat(cb.paid()).isTrue();
        assertThat(cb.eventId()).isEqualTo("evt_test_1");
    }

    @Test
    void unpaidStatusIsReportedAsNotPaid() throws Exception {
        String payload = event("\"amount_total\":5990,\"currency\":\"jpy\","
                + "\"payment_status\":\"unpaid\",\"metadata\":{\"orderId\":\"42\"}");
        PaymentCallback cb = provider().handleCallback(payload,
                Map.of("Stripe-Signature", sign(payload, Instant.now().getEpochSecond()))).orElseThrow();
        assertThat(cb.paid()).isFalse();
    }

    @Test
    void undeserializableSessionIsReportedForRetry() throws Exception {
        // amount_total が数値でないため Session に復元できない
        String payload = event("\"amount_total\":\"abc\",\"metadata\":{\"orderId\":\"42\"}");
        String sig = sign(payload, Instant.now().getEpochSecond());

        assertThatThrownBy(() -> provider().handleCallback(payload, Map.of("Stripe-Signature", sig)))
                .isInstanceOf(PaymentCallbackUnreadableException.class);
    }

    @Test
    void liveModeEventIsNotAcceptedAsPayment() throws Exception {
        // 署名は正しいが livemode=true。PAID にしない（コールバックを返さない）
        String payload = GOOD.replace("\"livemode\":false", "\"livemode\":true");
        assertThat(payload).contains("\"livemode\":true");

        assertThat(provider().handleCallback(payload,
                Map.of("Stripe-Signature", sign(payload, Instant.now().getEpochSecond())))).isEmpty();
    }

    @Test
    void oldSignatureIsRejected() throws Exception {
        long old = Instant.now().getEpochSecond() - 3600;
        String sig = sign(GOOD, old);

        assertThatThrownBy(() -> provider().handleCallback(GOOD, Map.of("Stripe-Signature", sig)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        String sig = sign(GOOD, Instant.now().getEpochSecond());
        String tampered = GOOD.replace("5990", "1");

        assertThatThrownBy(() -> provider().handleCallback(tampered, Map.of("Stripe-Signature", sig)))
                .isInstanceOf(BadRequestException.class);
    }
}
