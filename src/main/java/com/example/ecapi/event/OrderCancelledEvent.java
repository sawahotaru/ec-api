package com.example.ecapi.event;

import com.example.ecapi.domain.Order;
import java.math.BigDecimal;

/** 注文が明示的にキャンセルされた（管理者操作・利用者操作）。 */
public record OrderCancelledEvent(
        Long orderId,
        String contactEmail,
        BigDecimal totalAmount,
        String guestToken,
        /** キャンセル前のステータス。未払いキャンセルと支払後キャンセルを区別できる。 */
        String previousStatus) implements OrderEvent {

    public static OrderCancelledEvent of(Order order, String previousStatus) {
        return new OrderCancelledEvent(order.getId(), order.getContactEmail(),
                order.getTotalAmount(), order.getOrderToken(), previousStatus);
    }

    @Override
    public String type() {
        return "cancelled";
    }
}
