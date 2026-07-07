package com.example.ecapi.dto;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record OrderItemResponse(
            Long id,
            Long productId,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal) {

        public static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                    item.getId(),
                    item.getProduct().getId(),
                    item.getProductName(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    item.getLineTotal());
        }
    }

    public record OrderResponse(
            Long id,
            String userEmail,
            String status,
            BigDecimal totalAmount,
            List<OrderItemResponse> items,
            Instant createdAt) {

        public static OrderResponse from(Order order) {
            List<OrderItemResponse> items = order.getItems().stream()
                    .map(OrderItemResponse::from)
                    .toList();
            return new OrderResponse(
                    order.getId(),
                    order.getUser().getEmail(),
                    order.getStatus().name(),
                    order.getTotalAmount(),
                    items,
                    order.getCreatedAt());
        }
    }

    public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
    }
}
