package com.example.ecapi.service;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 支払いの期限（取り置きの期限）の決め方を 1 か所に集める。
 * 今は注文の作成時刻に保留の分数（{@code app.order.hold-minutes}）を足した時刻。PENDING 以外は期限なし。
 */
@Component
public class PaymentDeadlines {

    private final long holdMinutes;

    public PaymentDeadlines(@Value("${app.order.hold-minutes:30}") long holdMinutes) {
        this.holdMinutes = holdMinutes;
    }

    /** PENDING なら支払い期限、それ以外は null。 */
    public Instant payBy(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            return null;
        }
        return order.getCreatedAt().plus(Duration.ofMinutes(holdMinutes));
    }
}
