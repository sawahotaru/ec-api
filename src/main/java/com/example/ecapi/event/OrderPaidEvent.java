package com.example.ecapi.event;

import com.example.ecapi.domain.Order;
import java.math.BigDecimal;

/**
 * 支払いが確定し、在庫の引当（hold）が実在庫の減算に変換された。
 * 注文確認メール・受注通知・会計連携が載る主要なフック点。
 */
public record OrderPaidEvent(
        Long orderId,
        String contactEmail,
        BigDecimal totalAmount,
        String guestToken,
        /** 支払いに使われた決済手段の id（{@code "stripe"} 等）。手動確定なら null。 */
        String paymentProviderId) implements OrderEvent {

    public static OrderPaidEvent of(Order order) {
        return new OrderPaidEvent(order.getId(), order.getContactEmail(),
                order.getTotalAmount(), order.getOrderToken(), order.getPaymentProvider());
    }

    @Override
    public String type() {
        return "paid";
    }
}
