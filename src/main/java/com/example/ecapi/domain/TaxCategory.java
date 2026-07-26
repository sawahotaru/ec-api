package com.example.ecapi.domain;

/**
 * Which consumption-tax rate schedule applies to a product.
 * STANDARD = 標準税率（現行10%）, REDUCED = 軽減税率（現行8%：飲食料品・新聞など）。
 * The actual percentage lives in {@link TaxRate} with effective dates, so a rate
 * change is just a new TaxRate row — categories stay stable.
 */
public enum TaxCategory {
    STANDARD,
    REDUCED
}
