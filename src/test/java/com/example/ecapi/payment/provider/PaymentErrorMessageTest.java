package com.example.ecapi.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.payment.PaymentProperties;
import com.example.ecapi.payment.PaymentProvider;
import com.example.ecapi.payment.PaymentProviderRegistry;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 決済まわりの 400 の本文は固定文だけ。入力値・設定名・環境変数名・決済手段の一覧・
 * 例外の文言（キーの断片）を返さない。
 */
class PaymentErrorMessageTest {

    private static final String START_FAILED = "決済を開始できませんでした。時間をおいてお試しください。";
    private static final String REQUEST_FAILED = "要求を処理できませんでした";

    private static StripePaymentProvider stripe(String secretKey, String webhookSecret) {
        StripeProperties props = new StripeProperties();
        props.setSecretKey(secretKey);
        props.setWebhookSecret(webhookSecret);
        return new StripePaymentProvider(props, new PaymentProperties());
    }

    private static PaymentProviderRegistry registry(PaymentProvider... providers) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        for (int i = 0; i < providers.length; i++) {
            bf.registerSingleton("p" + i, providers[i]);
        }
        return new PaymentProviderRegistry(bf.getBeanProvider(PaymentProvider.class),
                new PaymentProperties());
    }

    @Test
    void unknownProviderMessageDoesNotEchoInputOrListProviders() {
        PaymentProviderRegistry registry = registry(stripe("sk_test_unit_dummy", "whsec_unit_test_dummy"));

        assertThatThrownBy(() -> registry.require("<script>x</script>"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("指定されたお支払い方法は利用できません")
                .message().doesNotContain("script", "stripe", "bank_transfer", "STRIPE_", "app.payment");
    }

    @Test
    void disabledProviderMessageDoesNotRevealConfiguration() {
        PaymentProviderRegistry registry = registry(stripe("", ""));

        assertThatThrownBy(() -> registry.require("stripe"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("指定されたお支払い方法は利用できません");
    }

    @Test
    void noEnabledProviderMessageDoesNotNameSettings() {
        PaymentProviderRegistry registry = registry(stripe("", ""));

        assertThatThrownBy(() -> registry.require(null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("利用できるお支払い方法がありません")
                .message().doesNotContain("STRIPE_", "app.payment", "bank");
    }

    @Test
    void stripeApiErrorIsMappedToFixedMessage() {
        String leaky = "Invalid API Key provided: " + "sk_test_" + "****abcd" + " (api_key)";
        StripeException e = new ApiException(leaky, "req_unit_1", "invalid_request", 401, null);

        BadRequestException mapped = StripePaymentProvider.clientError(e, 7L);

        assertThat(mapped.getMessage()).isEqualTo(START_FAILED);
        assertThat(mapped.getMessage()).doesNotContain("sk_test_", "api_key", "Stripe");
    }

    @Test
    void createSessionWithoutKeyUsesTheSameFixedMessage() {
        assertThatThrownBy(() -> stripe("", "").createSession(null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(START_FAILED);
    }

    @Test
    void webhookRejectionsDoNotMentionStripeOrSettings() {
        // 署名なし／不正／whsec 未設定は、どれも同じ固定文
        assertThatThrownBy(() -> stripe("sk_test_unit_dummy", "whsec_unit_test_dummy")
                .handleCallback("{}", Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessage(REQUEST_FAILED);
        assertThatThrownBy(() -> stripe("sk_test_unit_dummy", "whsec_unit_test_dummy")
                .handleCallback("{}", Map.of("Stripe-Signature", "t=1,v1=deadbeef")))
                .isInstanceOf(BadRequestException.class).hasMessage(REQUEST_FAILED);
        assertThatThrownBy(() -> stripe("sk_test_unit_dummy", "")
                .handleCallback("{}", Map.of("Stripe-Signature", "t=1,v1=deadbeef")))
                .isInstanceOf(BadRequestException.class).hasMessage(REQUEST_FAILED);
    }
}
