package com.example.ecapi.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.Order;
import com.example.ecapi.payment.PaymentProperties;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 決済後の戻り先: 設定値に既にクエリがあれば {@code &orderId=}、無ければ {@code ?orderId=} を足す。
 * 旧い設定値（/api/payments/*）でも新しい設定値（/?payment=*）でも、{@code ?} が 1 つだけの URL になる。
 * Stripe へは送らない。
 */
class StripeReturnUrlTest {

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({
            "http://x/?payment=success, success, http://x/?payment=success&orderId=12",
            "http://x/?payment=cancel, cancel, http://x/?payment=cancel&orderId=12",
            "http://x/api/payments/success, success, http://x/api/payments/success?orderId=12",
            "http://x/api/payments/cancel, cancel, http://x/api/payments/cancel?orderId=12"
    })
    void returnUrlsCarryOrderId(String configured, String kind, String expected) {
        StripeProperties props = new StripeProperties();
        props.setSuccessUrl(configured);
        props.setCancelUrl(configured);
        StripePaymentProvider provider = new StripePaymentProvider(props, new PaymentProperties());

        Order order = new Order();
        order.setId(12L);
        order.setTotalAmount(new BigDecimal("1000"));

        var params = provider.buildParams(order, "jpy");
        String actual = "success".equals(kind) ? params.getSuccessUrl() : params.getCancelUrl();

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.chars().filter(c -> c == '?').count()).isEqualTo(1);
    }
}
