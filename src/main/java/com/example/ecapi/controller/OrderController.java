package com.example.ecapi.controller;

import com.example.ecapi.domain.User;
import com.example.ecapi.dto.OrderDtos.OrderResponse;
import com.example.ecapi.security.CurrentUserProvider;
import com.example.ecapi.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Orders", description = "Checkout and the user's own order history")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserProvider currentUserProvider;

    public OrderController(OrderService orderService, CurrentUserProvider currentUserProvider) {
        this.orderService = orderService;
        this.currentUserProvider = currentUserProvider;
    }

    @Operation(summary = "Check out: turn the cart into an order")
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout() {
        User user = currentUserProvider.require();
        OrderResponse response = OrderResponse.from(orderService.checkout(user));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List my orders (paginated)")
    @GetMapping
    public Page<OrderResponse> myOrders(@PageableDefault(size = 10, sort = "createdAt") Pageable pageable) {
        return orderService.findMyOrders(currentUserProvider.require(), pageable).map(OrderResponse::from);
    }

    @Operation(summary = "Get one of my orders by id")
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getMyOrder(@PathVariable Long id) {
        return ResponseEntity.ok(OrderResponse.from(orderService.getMyOrder(currentUserProvider.require(), id)));
    }
}
