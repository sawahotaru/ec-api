package com.example.ecapi.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.Order;
import com.example.ecapi.payment.ExpireResult;
import com.example.ecapi.payment.PaymentProperties;
import org.junit.jupiter.api.Test;

/**
 * 失効の判定の順序（単体。Spring を起動しない）。注意: <strong>{@code cs_} の参照とテスト用の形のキー
 * （{@code sk_test_…}）を同時に与えない</strong>（与えると本物の Stripe を呼んでしまう）。
 * キーは常に空で、参照が {@code cs_} のときも通信は起きない。
 */
class StripeExpireResultTest {

    private final StripePaymentProvider provider =
            new StripePaymentProvider(new StripeProperties(), new PaymentProperties());

    private static Order orderWithReference(String reference) {
        Order order = new Order();
        order.setId(7L);
        order.setPaymentReference(reference);
        return order;
    }

    @Test
    void referenceIsCheckedBeforeKey() {
        // キーは無い。それでも、決済ページを持たない参照は「開いていない」であって「失敗」ではない
        assertThat(provider.expireOpenPayment(orderWithReference(null))).isEqualTo(ExpireResult.NOTHING_OPEN);
        assertThat(provider.expireOpenPayment(orderWithReference(""))).isEqualTo(ExpireResult.NOTHING_OPEN);
        assertThat(provider.expireOpenPayment(orderWithReference("BT-000007"))).isEqualTo(ExpireResult.NOTHING_OPEN);
    }

    @Test
    void sessionReferenceWithoutKeyIsUnknown() {
        assertThat(provider.expireOpenPayment(orderWithReference("cs_test_abc"))).isEqualTo(ExpireResult.FAILED);
    }

    @Test
    void sessionStatusMapsToResult() {
        assertThat(StripePaymentProvider.fromSessionStatus("complete")).isEqualTo(ExpireResult.ALREADY_COMPLETED);
        assertThat(StripePaymentProvider.fromSessionStatus("expired")).isEqualTo(ExpireResult.EXPIRED);
        assertThat(StripePaymentProvider.fromSessionStatus("open")).isEqualTo(ExpireResult.FAILED);
        assertThat(StripePaymentProvider.fromSessionStatus(null)).isEqualTo(ExpireResult.FAILED);
    }
}
