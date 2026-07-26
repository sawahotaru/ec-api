package com.example.ecapi.service;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.User;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final TaxService taxService;

    public OrderService(OrderRepository orderRepository,
                        CartItemRepository cartItemRepository,
                        ProductRepository productRepository,
                        TaxService taxService) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.taxService = taxService;
    }

    /**
     * Turns the logged-in user's cart into a PENDING order: snapshots price/name and
     * <em>reserves</em> (holds) stock — all in one transaction. The hold becomes a real
     * stock decrement only when payment is confirmed (see {@link PaymentService}); it is
     * released if the order is cancelled or expires unpaid.
     */
    @Transactional
    public Order checkout(User user) {
        List<CartItem> cartItems = cartItemRepository.findByUserId(user.getId());
        if (cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // Reserve in a consistent order (by product id) to avoid deadlocks when
        // different carts share items.
        Map<Long, Integer> quantities = new TreeMap<>();
        for (CartItem ci : cartItems) {
            quantities.merge(ci.getProduct().getId(), ci.getQuantity(), Integer::sum);
        }

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        buildAndReserve(order, quantities);

        Order saved = orderRepository.save(order);
        cartItemRepository.deleteByUserId(user.getId());
        return saved;
    }

    /**
     * Guest checkout: no account and no server-side cart. The caller passes the line
     * items directly. Stock is reserved exactly as for a logged-in checkout, and an
     * unguessable {@code orderToken} is issued so the guest can later view and pay the
     * order without logging in.
     *
     * @param email      guest contact email (validated at the DTO layer)
     * @param quantities productId → quantity (already de-duplicated / merged)
     */
    @Transactional
    public Order guestCheckout(String email, Map<Long, Integer> quantities) {
        if (quantities == null || quantities.isEmpty()) {
            throw new BadRequestException("No items to order");
        }
        // TreeMap keeps a stable product-id order (deadlock avoidance) even if the
        // caller passed an unordered map.
        Map<Long, Integer> ordered = new TreeMap<>(quantities);

        Order order = new Order();
        order.setGuestEmail(email);
        order.setOrderToken(UUID.randomUUID().toString());
        order.setStatus(OrderStatus.PENDING);
        buildAndReserve(order, ordered);

        return orderRepository.save(order);
    }

    /**
     * Snapshots each line (price/name AND tax rate/amount), reserves its stock, and
     * accumulates the order's subtotal / tax / total. The tax rate is resolved from the
     * effective-dated {@link TaxRate} table for each product's category as of today and
     * frozen onto the order — a later rate change never rewrites this order.
     */
    private void buildAndReserve(Order order, Map<Long, Integer> quantities) {
        PricingMode mode = taxService.pricingMode();
        LocalDate today = LocalDate.now();
        order.setPricingMode(mode);

        BigDecimal subtotal = BigDecimal.ZERO;  // 税抜合計
        BigDecimal taxTotal = BigDecimal.ZERO;  // 税額合計
        BigDecimal total = BigDecimal.ZERO;     // 税込合計（支払額）

        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();
            if (quantity < 1) {
                throw new BadRequestException("Quantity must be at least 1 for product " + productId);
            }
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new NotFoundException("Product no longer exists: " + productId));

            // Atomic conditional hold: only succeeds if enough is still sellable.
            // DB-level guard prevents overselling under concurrent checkouts.
            int updated = productRepository.reserveStock(productId, quantity);
            if (updated == 0) {
                throw new BadRequestException(
                        "Not enough stock for '" + product.getName() + "'. Available: " + product.getAvailable());
            }

            TaxCategory category = product.getTaxCategory();
            BigDecimal rate = taxService.rateFor(category, today);

            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setProductName(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(quantity);
            orderItem.setTaxCategory(category);
            orderItem.setTaxRatePercent(rate);

            BigDecimal lineListTotal = orderItem.getLineTotal(); // INCLUSIVE=税込 / EXCLUSIVE=税抜
            BigDecimal lineTax = taxService.taxForLine(lineListTotal, rate);
            orderItem.setTaxAmount(lineTax);
            order.addItem(orderItem);

            if (mode == PricingMode.INCLUSIVE) {
                total = total.add(lineListTotal);             // 税込
                taxTotal = taxTotal.add(lineTax);
                subtotal = subtotal.add(lineListTotal.subtract(lineTax)); // 税抜
            } else { // EXCLUSIVE: price は税抜
                subtotal = subtotal.add(lineListTotal);        // 税抜
                taxTotal = taxTotal.add(lineTax);
                total = total.add(lineListTotal.add(lineTax));  // 税込
            }
        }

        order.setSubtotalAmount(subtotal);
        order.setTaxAmount(taxTotal);
        order.setTotalAmount(total);
    }

    /**
     * Expires PENDING orders created before {@code cutoff}: releases each order's held
     * stock and marks it EXPIRED. Runs in one transaction; release is DB-guarded so it
     * stays correct even if two sweeps overlap. Returns the number of orders expired.
     */
    @Transactional
    public int expireStalePendingOrders(java.time.Instant cutoff) {
        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);
        for (Order order : stale) {
            releaseHeldItems(order);
            order.setStatus(OrderStatus.EXPIRED);
        }
        return stale.size();
    }

    @Transactional(readOnly = true)
    public Page<Order> findMyOrders(User user, Pageable pageable) {
        return orderRepository.findByUserId(user.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public Order getMyOrder(User user, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    /** Guest order lookup — the token authenticates the request in place of a login. */
    @Transactional(readOnly = true)
    public Order getGuestOrder(Long orderId, String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Order token is required");
        }
        return orderRepository.findByIdAndOrderToken(orderId, token)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Order get(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    @Transactional
    public Order updateStatus(Long orderId, OrderStatus status) {
        Order order = get(orderId);
        OrderStatus previous = order.getStatus();
        // Cancelling a still-unpaid order must give its held stock back. A PAID order
        // has already had its hold converted to a real decrement, so there is nothing
        // to release (and releasing would wrongly inflate stock).
        if (status == OrderStatus.CANCELLED && previous == OrderStatus.PENDING) {
            releaseHeldItems(order);
        }
        order.setStatus(status);
        return orderRepository.save(order);
    }

    private void releaseHeldItems(Order order) {
        for (OrderItem item : order.getItems()) {
            productRepository.releaseStock(item.getProduct().getId(), item.getQuantity());
        }
    }
}
