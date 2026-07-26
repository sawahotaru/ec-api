package com.example.ecapi.service;

import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Consumption-tax calculation. The rate for a category is resolved from
 * {@link TaxRate} by effective date, and the computed rate/amount are meant to be
 * <em>snapshotted</em> onto the order — so a later rate change never rewrites past
 * orders. Rounding is per-line truncation (切り捨て) to whole yen, the common JP retail
 * convention; adjust {@code app.tax.rounding} if a shop prefers HALF_UP.
 */
@Service
public class TaxService {

    private static final Logger log = LoggerFactory.getLogger(TaxService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TaxRateRepository taxRateRepository;
    private final PricingMode pricingMode;
    private final RoundingMode rounding;

    public TaxService(TaxRateRepository taxRateRepository,
                      @Value("${app.tax.pricing-mode:INCLUSIVE}") PricingMode pricingMode,
                      @Value("${app.tax.rounding:FLOOR}") RoundingMode rounding) {
        this.taxRateRepository = taxRateRepository;
        this.pricingMode = pricingMode;
        this.rounding = rounding;
    }

    public PricingMode pricingMode() {
        return pricingMode;
    }

    /** The percentage in effect for a category on a date (0 if none configured). */
    public BigDecimal rateFor(TaxCategory category, LocalDate date) {
        List<TaxRate> hits = taxRateRepository.findEffective(category, date);
        if (hits.isEmpty()) {
            log.warn("No tax rate configured for {} on {} — treating as 0%", category, date);
            return BigDecimal.ZERO;
        }
        return hits.get(0).getRatePercent();
    }

    /**
     * Tax for a whole line, rounded to yen.
     * @param lineAmount in INCLUSIVE mode the tax-included line total; in EXCLUSIVE
     *                   mode the tax-exclusive (net) line total.
     * @param ratePercent e.g. 10.00
     */
    public BigDecimal taxForLine(BigDecimal lineAmount, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal tax;
        if (pricingMode == PricingMode.INCLUSIVE) {
            // price is tax-included: tax = amount * rate / (100 + rate)
            tax = lineAmount.multiply(ratePercent)
                    .divide(HUNDRED.add(ratePercent), 0, rounding);
        } else {
            // price is tax-exclusive: tax = amount * rate / 100
            tax = lineAmount.multiply(ratePercent)
                    .divide(HUNDRED, 0, rounding);
        }
        return tax.setScale(2);
    }
}
