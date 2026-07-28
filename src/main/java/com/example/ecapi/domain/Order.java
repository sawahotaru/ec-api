package com.example.ecapi.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The buyer, when the order was placed while logged in. Null for guest
     * checkout — in that case {@link #guestEmail} and {@link #orderToken}
     * identify the order instead.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id")
    private User user;

    /** Contact email for a guest order. Null for a logged-in order. */
    private String guestEmail;

    /**
     * Unguessable token handed back once at guest checkout. A guest presents it
     * to view or pay their order (they have no account to authenticate with).
     * Null for logged-in orders.
     */
    @Column(unique = true)
    private String orderToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    /** 税抜合計（subtotal, tax-exclusive）. Snapshotted at checkout. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    /** 消費税額合計. Snapshotted at checkout. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** 税込合計（支払総額）= subtotal + tax. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 内税/外税どちらで計算したか（注文時点をスナップショット）. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingMode pricingMode = PricingMode.INCLUSIVE;

    /**
     * どの決済手段で支払われた（支払おうとした）か —— {@code PaymentProvider#id()} の値
     * （{@code "stripe"} / {@code "bank_transfer"}）。支払い開始前、および管理者が手動で
     * PAID にした場合は null。
     *
     * <p>以前はここが {@code stripeSessionId} という Stripe 固有のカラムだった。決済業者名が
     * 注文テーブルのスキーマに焼き付いている状態だったため、汎用の2カラムに置き換えている。
     */
    private String paymentProvider;

    /** 決済側の参照ID（Stripe の Checkout Session id、銀行振込の照合番号など）。照合用。 */
    private String paymentReference;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public void addItem(OrderItem item) {
        item.setOrder(this);
        this.items.add(item);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getGuestEmail() {
        return guestEmail;
    }

    public void setGuestEmail(String guestEmail) {
        this.guestEmail = guestEmail;
    }

    public String getOrderToken() {
        return orderToken;
    }

    public void setOrderToken(String orderToken) {
        this.orderToken = orderToken;
    }

    /** Email of whoever placed the order — the account email, or the guest email. */
    public String getContactEmail() {
        return user != null ? user.getEmail() : guestEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getSubtotalAmount() {
        return subtotalAmount;
    }

    public void setSubtotalAmount(BigDecimal subtotalAmount) {
        this.subtotalAmount = subtotalAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public PricingMode getPricingMode() {
        return pricingMode;
    }

    public void setPricingMode(PricingMode pricingMode) {
        this.pricingMode = pricingMode;
    }

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public void setPaymentProvider(String paymentProvider) {
        this.paymentProvider = paymentProvider;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
