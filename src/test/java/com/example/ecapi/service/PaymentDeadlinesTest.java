package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** 支払い期限は PENDING の注文だけが持つ（作成時刻＋保留の分数。振込を選んだ注文は＋振込の日数）。 */
class PaymentDeadlinesTest {

    private final PaymentDeadlines deadlines = new PaymentDeadlines(30, 14, Clock.systemUTC());

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void onlyPendingOrdersHaveDeadline(OrderStatus status) {
        Order order = new Order();
        order.setStatus(status);

        if (status == OrderStatus.PENDING) {
            assertThat(deadlines.payBy(order))
                    .isEqualTo(order.getCreatedAt().plus(Duration.ofMinutes(30)));
        } else {
            assertThat(deadlines.payBy(order)).isNull();
        }
    }

    @Test
    void bankTransferOrderKeepsFourteenDays() {
        Order order = new Order();
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentProvider("bank_transfer");

        assertThat(deadlines.payBy(order)).isEqualTo(order.getCreatedAt().plus(Duration.ofDays(14)));
    }

    @Test
    void cardOrderStaysThirtyMinutes() {
        Order order = new Order();
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentProvider("stripe");

        assertThat(deadlines.payBy(order)).isEqualTo(order.getCreatedAt().plus(Duration.ofMinutes(30)));
    }
}
