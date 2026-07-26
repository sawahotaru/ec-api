package com.example.ecapi.dto;

import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TaxDtos {

    private TaxDtos() {
    }

    /** Admin create/update of an effective-dated tax rate. */
    public record TaxRateRequest(
            @NotNull TaxCategory category,
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal ratePercent,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record TaxRateResponse(
            Long id,
            String category,
            BigDecimal ratePercent,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {

        public static TaxRateResponse from(TaxRate r) {
            return new TaxRateResponse(
                    r.getId(), r.getCategory().name(), r.getRatePercent(),
                    r.getEffectiveFrom(), r.getEffectiveTo());
        }
    }

    /** Public: how prices relate to tax + the currently-effective rate per category. */
    public record TaxConfigResponse(
            String pricingMode,
            List<CurrentRate> rates) {

        public record CurrentRate(String category, BigDecimal ratePercent) {
        }
    }

    /** Admin: switch the tax pricing mode at runtime. */
    public record PricingModeRequest(@NotNull PricingMode pricingMode) {
    }

    public record SettingsResponse(String pricingMode) {
    }
}
