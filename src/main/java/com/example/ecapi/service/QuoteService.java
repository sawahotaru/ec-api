package com.example.ecapi.service;

import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.Product;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.pricing.OrderPricer;
import com.example.ecapi.pricing.OrderPricing;
import com.example.ecapi.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a cart without creating anything.
 *
 * <p>This is what the staging in {@link OrderPricer} bought: the shop can show 送料 and
 * クーポン割引 in the cart before checkout, computed by <strong>the same code that will
 * charge the customer</strong>. A separate "estimate" implementation on the front end is
 * how a cart comes to show a total the invoice then contradicts.
 *
 * <p>No stock is reserved and no redemption is claimed here — a quote is a question, not
 * a commitment. Checkout can still fail afterwards (sold out, coupon just used up), and
 * that is the correct place for it to fail.
 */
@Service
public class QuoteService {

    private final ProductRepository productRepository;
    private final CouponService couponService;
    private final OrderPricer orderPricer;

    public QuoteService(ProductRepository productRepository,
                        CouponService couponService,
                        OrderPricer orderPricer) {
        this.productRepository = productRepository;
        this.couponService = couponService;
        this.orderPricer = orderPricer;
    }

    @Transactional(readOnly = true)
    public OrderPricing quote(Map<Long, Integer> quantities, String couponCode) {
        if (quantities == null || quantities.isEmpty()) {
            throw new BadRequestException("No items to quote");
        }
        LocalDate today = LocalDate.now();

        Map<Product, Integer> lines = new LinkedHashMap<>();
        BigDecimal itemsList = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            int quantity = entry.getValue();
            if (quantity < 1) {
                throw new BadRequestException("Quantity must be at least 1 for product " + entry.getKey());
            }
            Product product = productRepository.findById(entry.getKey())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + entry.getKey()));
            lines.put(product, quantity);
            itemsList = itemsList.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        Coupon coupon = couponService.validate(couponCode, itemsList, today);
        return orderPricer.price(lines, coupon, today);
    }
}
