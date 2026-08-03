package com.example.ecapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.ColumnDefault;

/**
 * A line in an order. Product name and unit price are snapshotted at purchase
 * time so later product edits do not rewrite order history.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    // --- tax snapshot (fixed at purchase time; immune to later rate changes) ---
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaxCategory taxCategory = TaxCategory.STANDARD;

    /** Applied percentage at purchase time, e.g. 10.00. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent = BigDecimal.ZERO;

    /** Consumption tax for this line (whole yen), computed <em>after</em> the discount below. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /**
     * This line's share of the order's coupon discount, in the same convention as
     * {@link #getLineTotal()}.
     *
     * <p>Stored per line, not just per order, because the tax was computed on
     * {@code lineTotal − discountAmount}. Without it the line breakdown does not
     * reconcile: the tax would look wrong for the amount shown next to it.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    @ColumnDefault("0")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /**
     * Line total the customer is charged. In INCLUSIVE mode {@code unitPrice} is
     * tax-included, so this equals the tax-included line amount; in EXCLUSIVE mode
     * the tax has already been added into {@code taxAmount} and the paid total is
     * net + tax. Net (tax-exclusive) line = {@code getLineTotal() - taxAmount} in
     * INCLUSIVE mode.
     */
    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public TaxCategory getTaxCategory() {
        return taxCategory;
    }

    public void setTaxCategory(TaxCategory taxCategory) {
        this.taxCategory = taxCategory;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }
}
