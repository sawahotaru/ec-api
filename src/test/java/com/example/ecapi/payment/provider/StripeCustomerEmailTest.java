package com.example.ecapi.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.User;
import com.example.ecapi.payment.PaymentProperties;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 決済ページへ注文のメールを引き継ぐ（会員・ゲストとも）。空・800 文字超は渡さない。
 * 説明欄にはメールを入れない。Stripe へは送らない。
 */
class StripeCustomerEmailTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "guest; guest@example.com; guest@example.com",
            "member; member@example.com; member@example.com",
            "guestNull; NULL; NULL",
            "guestBlank; '  '; NULL",
            "tooLong; LONG; NULL"
    }, delimiter = ';', nullValues = "NULL")
    void customerEmailIsPassedOnlyWhenUsable(String kind, String email, String expected) {
        String value = "LONG".equals(email) ? "a".repeat(789) + "@example.com" : email;
        Order order = new Order();
        order.setId(7L);
        order.setTotalAmount(new BigDecimal("1000"));
        if ("member".equals(kind)) {
            User user = new User();
            user.setEmail(value);
            order.setUser(user);
        } else {
            order.setGuestEmail(value);
        }

        StripeProperties props = new StripeProperties();
        props.setSuccessUrl("http://x/?payment=success");
        props.setCancelUrl("http://x/?payment=cancel");
        var params = new StripePaymentProvider(props, new PaymentProperties()).buildParams(order, "jpy");

        assertThat(params.getCustomerEmail()).isEqualTo(expected);
        String description = params.getLineItems().get(0).getPriceData().getProductData().getDescription();
        assertThat(description).doesNotContain("@");
    }
}
