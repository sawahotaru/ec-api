package com.example.ecapi.service;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.CartDtos.CartItemResponse;
import com.example.ecapi.dto.CartDtos.CartResponse;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    public CartService(CartItemRepository cartItemRepository, ProductRepository productRepository) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public CartResponse getCart(User user) {
        return toResponse(cartItemRepository.findByUserId(user.getId()));
    }

    @Transactional
    public CartResponse addItem(User user, Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));

        CartItem item = cartItemRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElse(null);
        int newQuantity = quantity + (item == null ? 0 : item.getQuantity());
        ensureStock(product, newQuantity);

        if (item == null) {
            item = new CartItem();
            item.setUser(user);
            item.setProduct(product);
        }
        item.setQuantity(newQuantity);
        cartItemRepository.save(item);

        return getCart(user);
    }

    @Transactional
    public CartResponse updateItem(User user, Long productId, int quantity) {
        CartItem item = cartItemRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElseThrow(() -> new NotFoundException("Item not in cart: product " + productId));
        ensureStock(item.getProduct(), quantity);
        item.setQuantity(quantity);
        cartItemRepository.save(item);
        return getCart(user);
    }

    @Transactional
    public CartResponse removeItem(User user, Long productId) {
        CartItem item = cartItemRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElseThrow(() -> new NotFoundException("Item not in cart: product " + productId));
        cartItemRepository.delete(item);
        return getCart(user);
    }

    @Transactional
    public void clear(User user) {
        cartItemRepository.deleteByUserId(user.getId());
    }

    private void ensureStock(Product product, int requested) {
        if (requested > product.getStock()) {
            throw new BadRequestException(
                    "Not enough stock for '" + product.getName() + "'. Available: " + product.getStock());
        }
    }

    private CartResponse toResponse(List<CartItem> items) {
        List<CartItemResponse> itemResponses = items.stream()
                .map(CartItemResponse::from)
                .toList();
        int totalQuantity = itemResponses.stream().mapToInt(CartItemResponse::quantity).sum();
        BigDecimal totalAmount = itemResponses.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(itemResponses, totalQuantity, totalAmount);
    }
}
