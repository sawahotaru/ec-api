package com.example.ecapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An effective-dated consumption-tax rate for a {@link TaxCategory}.
 * A rate change is modelled by closing the current row ({@code effectiveTo}) and
 * inserting a new one — so the correct rate for any date is recoverable, and orders
 * snapshot the rate that applied at purchase time (past orders never change).
 */
@Entity
@Table(name = "tax_rates")
public class TaxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaxCategory category;

    /** Percentage, e.g. 10.00 or 8.00. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal ratePercent;

    /** Inclusive start date. */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /** Exclusive end date; null = still in effect (open-ended). */
    private LocalDate effectiveTo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TaxCategory getCategory() {
        return category;
    }

    public void setCategory(TaxCategory category) {
        this.category = category;
    }

    public BigDecimal getRatePercent() {
        return ratePercent;
    }

    public void setRatePercent(BigDecimal ratePercent) {
        this.ratePercent = ratePercent;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }
}
