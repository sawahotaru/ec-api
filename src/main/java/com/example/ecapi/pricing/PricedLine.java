package com.example.ecapi.pricing;

import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import java.math.BigDecimal;

/**
 * One order line after pricing: what it lists for, what came off it, and the tax
 * that results.
 *
 * @param product      the product being bought
 * @param quantity     how many
 * @param unitPrice    snapshotted unit price (税込 in INCLUSIVE mode, 税抜 in EXCLUSIVE)
 * @param taxCategory  which rate schedule applied
 * @param taxRate      the percentage that applied, e.g. 10.00
 * @param listAmount   {@code unitPrice × quantity} before any discount
 * @param discount     this line's share of the order discount, in the same convention as {@code listAmount}
 * @param tax          consumption tax, computed on {@code listAmount − discount}
 */
public record PricedLine(
        Product product,
        int quantity,
        BigDecimal unitPrice,
        TaxCategory taxCategory,
        BigDecimal taxRate,
        BigDecimal listAmount,
        BigDecimal discount,
        BigDecimal tax) {

    /** What this line lists for after its share of the discount. */
    public BigDecimal discountedAmount() {
        return listAmount.subtract(discount);
    }
}
