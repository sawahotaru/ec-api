package com.example.ecapi.dto;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.dto.ProductDtos.ProductResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public final class CartDtos {

    private CartDtos() {
    }

    public record AddCartItemRequest(
            @NotNull Long productId,
            @Min(1) int quantity) {
    }

    public record UpdateCartItemRequest(
            @Min(1) int quantity) {
    }

    public record CartItemResponse(
            Long id,
            ProductResponse product,
            int quantity,
            BigDecimal lineTotal) {

        public static CartItemResponse from(CartItem item) {
            BigDecimal lineTotal = item.getProduct().getPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            return new CartItemResponse(
                    item.getId(),
                    ProductResponse.from(item.getProduct()),
                    item.getQuantity(),
                    lineTotal);
        }
    }

    public record CartResponse(
            List<CartItemResponse> items,
            int totalQuantity,
            BigDecimal totalAmount) {
    }
}
