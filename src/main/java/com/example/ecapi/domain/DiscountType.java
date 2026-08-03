package com.example.ecapi.domain;

/** How a {@link Coupon}'s value is applied. */
public enum DiscountType {

    /** {@code value} percent off the item subtotal, e.g. 10.00 → 10% off. */
    PERCENT,

    /**
     * {@code value} yen off the item subtotal.
     *
     * <p>Read in the same convention as product prices: in 内税 mode it comes off the
     * tax-included amount the shopper sees, in 外税 mode off the tax-exclusive one.
     * "500円引き" should take 500 off the number printed next to the price, whichever
     * that number means in this shop.
     */
    FIXED,

    /** Shipping only. {@code value} is ignored; the items are not discounted. */
    FREE_SHIPPING
}
