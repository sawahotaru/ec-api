package com.example.ecapi.controller;

import com.example.ecapi.dto.CartDtos.AddCartItemRequest;
import com.example.ecapi.dto.CartDtos.CartResponse;
import com.example.ecapi.dto.CartDtos.UpdateCartItemRequest;
import com.example.ecapi.security.CurrentUserProvider;
import com.example.ecapi.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cart", description = "The authenticated user's shopping cart")
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;
    private final CurrentUserProvider currentUserProvider;

    public CartController(CartService cartService, CurrentUserProvider currentUserProvider) {
        this.cartService = cartService;
        this.currentUserProvider = currentUserProvider;
    }

    @Operation(summary = "View the cart")
    @GetMapping
    public CartResponse getCart() {
        return cartService.getCart(currentUserProvider.require());
    }

    @Operation(summary = "Add a product to the cart (adds to existing quantity)")
    @PostMapping("/items")
    public CartResponse addItem(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(currentUserProvider.require(), request.productId(), request.quantity());
    }

    @Operation(summary = "Set the quantity of a product in the cart")
    @PutMapping("/items/{productId}")
    public CartResponse updateItem(@PathVariable Long productId,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItem(currentUserProvider.require(), productId, request.quantity());
    }

    @Operation(summary = "Remove a product from the cart")
    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@PathVariable Long productId) {
        return cartService.removeItem(currentUserProvider.require(), productId);
    }

    @Operation(summary = "Empty the cart")
    @DeleteMapping
    public ResponseEntity<Void> clear() {
        cartService.clear(currentUserProvider.require());
        return ResponseEntity.noContent().build();
    }
}
