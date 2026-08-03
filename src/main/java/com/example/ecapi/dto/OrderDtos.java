package com.example.ecapi.dto;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.privacy.ContactMask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    /** One line of a guest order: which product, how many. */
    public record GuestCheckoutLine(
            @NotNull Long productId,
            @Min(1) int quantity) {
    }

    /** Guest checkout payload — no account, items passed inline (no server-side cart). */
    public record GuestCheckoutRequest(
            @NotNull @Email String email,
            @NotEmpty @Valid List<GuestCheckoutLine> items,
            // 任意。空・未指定なら割引なし。
            String couponCode) {
    }

    /** Logged-in checkout payload. The cart is server-side; only the coupon comes in. */
    public record CheckoutRequest(String couponCode) {
    }

    public record OrderItemResponse(
            Long id,
            Long productId,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            // この行が負担したクーポン割引（lineTotal と同じ流儀）。税は lineTotal − これ に対して計算済み。
            BigDecimal discountAmount,
            // tax snapshot (fixed at purchase time)
            String taxCategory,
            BigDecimal taxRatePercent,
            BigDecimal taxAmount) {

        public static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                    item.getId(),
                    item.getProduct().getId(),
                    item.getProductName(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.getLineTotal(),
                    item.getDiscountAmount(),
                    item.getTaxCategory().name(),
                    item.getTaxRatePercent(),
                    item.getTaxAmount());
        }
    }

    public record OrderResponse(
            Long id,
            String userEmail,
            boolean guest,
            String status,
            // 段階: 小計（割引前・税抜） − 割引 ＋ 送料 ＋ 税 = 合計
            BigDecimal subtotalAmount,
            BigDecimal discountAmount,
            BigDecimal shippingAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String couponCode,
            String pricingMode,
            List<OrderItemResponse> items,
            Instant createdAt,
            // Only present in the response to a guest checkout — the token the guest uses
            // to view or pay the order later. Null for logged-in orders and list views.
            String orderToken) {

        public static OrderResponse from(Order order) {
            return build(order, false);
        }

        /**
         * 連絡先を伏せ字にした複製。
         *
         * <p>使うのは<strong>管理画面の応答だけ</strong>。本人の注文照会（会員の
         * {@code /api/orders/{id}}・ゲストの token 照合）では伏せない——自分の
         * メールアドレスが読めないのは、保護ではなく不具合にしか見えない。
         */
        public OrderResponse masked() {
            return new OrderResponse(id, ContactMask.mask(userEmail), guest, status,
                    subtotalAmount, discountAmount, shippingAmount, taxAmount, totalAmount,
                    couponCode, pricingMode, items, createdAt, orderToken);
        }

        /** Includes the one-time orderToken — use only in the immediate guest-checkout reply. */
        public static OrderResponse fromWithToken(Order order) {
            return build(order, true);
        }

        private static OrderResponse build(Order order, boolean includeToken) {
            List<OrderItemResponse> items = order.getItems().stream()
                    .map(OrderItemResponse::from)
                    .toList();
            boolean isGuest = order.getUser() == null;
            return new OrderResponse(
                    order.getId(),
                    order.getContactEmail(),
                    isGuest,
                    order.getStatus().name(),
                    order.getSubtotalAmount(),
                    order.getDiscountAmount(),
                    order.getShippingAmount(),
                    order.getTaxAmount(),
                    order.getTotalAmount(),
                    order.getCouponCode(),
                    order.getPricingMode().name(),
                    items,
                    order.getCreatedAt(),
                    includeToken ? order.getOrderToken() : null);
        }
    }

    public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
    }
}
