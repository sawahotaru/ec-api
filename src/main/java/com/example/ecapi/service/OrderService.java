package com.example.ecapi.service;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.User;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository,
                        CartItemRepository cartItemRepository,
                        ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    /**
     * Turns the user's cart into an order: validates stock, snapshots price/name,
     * decrements stock, and clears the cart — all in one transaction.
     */
    @Transactional
    public Order checkout(User user) {
        List<CartItem> cartItems = cartItemRepository.findByUserId(user.getId());
        if (cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // Decrement stock in a consistent order (by product id) to avoid deadlocks when
        // different carts share items.
        cartItems.sort(Comparator.comparing(ci -> ci.getProduct().getId()));

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        BigDecimal total = BigDecimal.ZERO;
        for (CartItem cartItem : cartItems) {
            Long productId = cartItem.getProduct().getId();
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new NotFoundException(
                            "Product no longer exists: " + productId));

            // Atomic conditional decrement: only succeeds if enough stock remains.
            // DB-level guard prevents overselling under concurrent checkouts.
            int updated = productRepository.decrementStock(productId, cartItem.getQuantity());
            if (updated == 0) {
                throw new BadRequestException(
                        "Not enough stock for '" + product.getName() + "'. Available: " + product.getStock());
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setProductName(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            order.addItem(orderItem);

            total = total.add(orderItem.getLineTotal());
        }

        order.setTotalAmount(total);
        Order saved = orderRepository.save(order);
        cartItemRepository.deleteByUserId(user.getId());
        return saved;
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
        order.setStatus(status);
        return orderRepository.save(order);
    }
}
