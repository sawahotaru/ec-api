package com.example.ecapi.dto;

import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.dto.CategoryDtos.CategoryResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record ProductRequest(
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
            @PositiveOrZero int stock,
            String imageUrl,
            Long categoryId,
            // null → STANDARD（標準税率）。REDUCED で軽減税率。
            TaxCategory taxCategory) {
    }

    public record ProductResponse(
            Long id,
            String name,
            String description,
            BigDecimal price,
            int stock,
            // Sellable right now: stock minus units held for unpaid (pending) orders.
            int available,
            String imageUrl,
            String taxCategory,
            CategoryResponse category,
            Instant createdAt) {

        public static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.getId(),
                    product.getName(),
                    product.getDescription(),
                    product.getPrice(),
                    product.getStock(),
                    product.getAvailable(),
                    product.getImageUrl(),
                    product.getTaxCategory().name(),
                    CategoryResponse.from(product.getCategory()),
                    product.getCreatedAt());
        }
    }
}
