package com.example.ecapi.event;

import com.example.ecapi.domain.Order;
import java.math.BigDecimal;

/**
 * 保留期間内に支払われず自動失効した（引当在庫は販売可能へ戻済み）。
 * 「カゴ落ち」リマインドメールを載せるならここ。
 */
public record OrderExpiredEvent(
        Long orderId,
        String contactEmail,
        BigDecimal totalAmount,
        String guestToken) implements OrderEvent {

    public static OrderExpiredEvent of(Order order) {
        return new OrderExpiredEvent(order.getId(), order.getContactEmail(),
                order.getTotalAmount(), order.getOrderToken());
    }

    @Override
    public String type() {
        return "expired";
    }
}
