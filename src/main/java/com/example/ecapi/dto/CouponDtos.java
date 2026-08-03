package com.example.ecapi.dto;

import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class CouponDtos {

    private CouponDtos() {
    }

    public record CouponRequest(
            @NotBlank String code,
            String description,
            @NotNull DiscountType discountType,
            @NotNull @PositiveOrZero BigDecimal value,
            BigDecimal minSubtotal,
            LocalDate validFrom,
            // 排他（TaxRate と同じ）。この日はもう使えない。
            LocalDate validTo,
            Integer maxRedemptions,
            boolean enabled) {

        public Coupon toEntity() {
            Coupon coupon = new Coupon();
            coupon.setCode(code);
            coupon.setDescription(description);
            coupon.setDiscountType(discountType);
            coupon.setValue(value);
            coupon.setMinSubtotal(minSubtotal);
            coupon.setValidFrom(validFrom);
            coupon.setValidTo(validTo);
            coupon.setMaxRedemptions(maxRedemptions);
            coupon.setEnabled(enabled);
            return coupon;
        }
    }

    public record CouponResponse(
            Long id,
            String code,
            String description,
            String discountType,
            BigDecimal value,
            BigDecimal minSubtotal,
            LocalDate validFrom,
            LocalDate validTo,
            Integer maxRedemptions,
            int redeemedCount,
            boolean enabled,
            // 今日この時点で使える状態か（期限・上限・有効フラグをまとめた判定）。
            boolean activeToday) {

        public static CouponResponse from(Coupon coupon) {
            return new CouponResponse(
                    coupon.getId(),
                    coupon.getCode(),
                    coupon.getDescription(),
                    coupon.getDiscountType().name(),
                    coupon.getValue(),
                    coupon.getMinSubtotal(),
                    coupon.getValidFrom(),
                    coupon.getValidTo(),
                    coupon.getMaxRedemptions(),
                    coupon.getRedeemedCount(),
                    coupon.isEnabled(),
                    coupon.isActiveOn(LocalDate.now()));
        }
    }
}
