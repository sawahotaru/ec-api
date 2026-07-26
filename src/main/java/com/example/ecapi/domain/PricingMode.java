package com.example.ecapi.domain;

/**
 * How a product's price relates to tax.
 * INCLUSIVE = 内税（price は税込。総額表示。日本の小売で主流）,
 * EXCLUSIVE = 外税（price は税抜。税を加算して請求）。
 * The mode in effect at checkout is snapshotted onto the order so later changes
 * do not rewrite past orders.
 */
public enum PricingMode {
    INCLUSIVE,
    EXCLUSIVE
}
