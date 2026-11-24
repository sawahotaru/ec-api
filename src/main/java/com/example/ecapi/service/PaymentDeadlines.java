package com.example.ecapi.service;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 支払いの期限（取り置きの期限）の決め方を 1 か所に集める。
 * 注文の作成時刻に、カードは保留の分数（{@code app.order.hold-minutes}）、銀行振込を選んだ注文は
 * 振込の日数（{@code app.order.bank-transfer-hold-days}）を足した時刻。PENDING 以外は期限なし。
 */
@Component
public class PaymentDeadlines {

    /** 銀行振込の決済手段 id（{@code BankTransferPaymentProvider#id()} と同じ。DB に保存される値）。 */
    public static final String BANK_TRANSFER = "bank_transfer";

    private final long holdMinutes;
    private final long bankTransferHoldDays;
    private final Clock clock;

    public PaymentDeadlines(@Value("${app.order.hold-minutes:30}") long holdMinutes,
                            @Value("${app.order.bank-transfer-hold-days:14}") long bankTransferHoldDays,
                            Clock clock) {
        this.holdMinutes = holdMinutes;
        this.bankTransferHoldDays = bankTransferHoldDays;
        this.clock = clock;
    }

    /** PENDING なら支払い期限、それ以外は null。振込を選んだ注文だけ長い。 */
    public Instant payBy(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            return null;
        }
        return BANK_TRANSFER.equals(order.getPaymentProvider())
                ? bankTransferDeadline(order)
                : cardDeadline(order);
    }

    /** 振込を選んだときの期限（作成時刻 + 振込の日数）。決済手段の記録がまだ無い注文にも使える。 */
    public Instant bankTransferDeadline(Order order) {
        return order.getCreatedAt().plus(Duration.ofDays(bankTransferHoldDays));
    }

    /** カードの期限（作成時刻 + 保留の分数）。 */
    public Instant cardDeadline(Order order) {
        return order.getCreatedAt().plus(Duration.ofMinutes(holdMinutes));
    }

    /** カードを始められる期間（作成から保留の分数）を過ぎたか。 */
    public boolean cardWindowClosed(Order order) {
        return clock.instant().isAfter(cardDeadline(order));
    }
}
