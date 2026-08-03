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
import java.time.Instant;
import java.time.LocalDate;

/**
 * A discount code a shopper can enter at checkout.
 *
 * <p>Dates follow the same convention as {@link TaxRate}: {@code validFrom} is inclusive,
 * {@code validTo} is <strong>exclusive</strong>. One convention for "a period" across the
 * whole app is worth more than each table picking what reads nicest, because the
 * off-by-one only shows up on the last day.
 *
 * <p>{@code redeemedCount} is incremented by a conditional UPDATE at checkout — the same
 * idiom as the stock hold — so a code with 100 uses cannot be redeemed 101 times by
 * concurrent checkouts. It is decremented again if the order is cancelled or expires
 * unpaid, because those orders never became sales.
 */
@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored upper-case; lookups upper-case the input so entry is case-insensitive. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType discountType = DiscountType.FIXED;

    /**
     * Percent (PERCENT) or yen (FIXED). Ignored for FREE_SHIPPING.
     *
     * <p>The column is {@code discount_value}, not {@code value}: the latter is a reserved
     * word in H2, so {@code CREATE TABLE} fails there while working fine on PostgreSQL —
     * i.e. it would have been schema that only exists in production.
     */
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal value = BigDecimal.ZERO;

    /** Minimum item subtotal for the code to apply. Null = no minimum. */
    @Column(precision = 12, scale = 2)
    private BigDecimal minSubtotal;

    /** Inclusive start date. Null = no start bound. */
    private LocalDate validFrom;

    /** Exclusive end date. Null = open-ended. */
    private LocalDate validTo;

    /** Total redemptions allowed. Null = unlimited. */
    private Integer maxRedemptions;

    @Column(nullable = false)
    private int redeemedCount = 0;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** True if the code is usable on this date, ignoring the cart's contents. */
    public boolean isActiveOn(LocalDate date) {
        if (!enabled) {
            return false;
        }
        if (validFrom != null && date.isBefore(validFrom)) {
            return false;
        }
        if (validTo != null && !date.isBefore(validTo)) {
            return false;
        }
        return !isExhausted();
    }

    public boolean isExhausted() {
        return maxRedemptions != null && redeemedCount >= maxRedemptions;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public void setDiscountType(DiscountType discountType) {
        this.discountType = discountType;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getMinSubtotal() {
        return minSubtotal;
    }

    public void setMinSubtotal(BigDecimal minSubtotal) {
        this.minSubtotal = minSubtotal;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public Integer getMaxRedemptions() {
        return maxRedemptions;
    }

    public void setMaxRedemptions(Integer maxRedemptions) {
        this.maxRedemptions = maxRedemptions;
    }

    public int getRedeemedCount() {
        return redeemedCount;
    }

    public void setRedeemedCount(int redeemedCount) {
        this.redeemedCount = redeemedCount;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
