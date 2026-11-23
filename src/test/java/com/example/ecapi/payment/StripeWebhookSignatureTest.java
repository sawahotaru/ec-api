package com.example.ecapi.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.payment.provider.StripePaymentProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Webhook の受け口は公開なので、署名の無い／壊れた要求は 400 で返すこと。
 * 署名ヘッダが無いと SDK 内部で NullPointerException になり 500 を返していた。
 */
class StripeWebhookSignatureTest {

    private StripePaymentProvider provider() {
        StripeProperties props = new StripeProperties();
        props.setWebhookSecret("whsec_unit_test_dummy");
        return new StripePaymentProvider(props, new PaymentProperties());
    }

    @Test
    void missingSignatureHeaderIsBadRequest() {
        assertThatThrownBy(() -> provider().handleCallback("{}", Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void blankSignatureHeaderIsBadRequest() {
        assertThatThrownBy(() -> provider().handleCallback("{}", Map.of("Stripe-Signature", " ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void forgedSignatureIsBadRequest() {
        assertThatThrownBy(() -> provider().handleCallback("{}",
                Map.of("Stripe-Signature", "t=1,v1=deadbeef")))
                .isInstanceOf(BadRequestException.class);
    }
}
