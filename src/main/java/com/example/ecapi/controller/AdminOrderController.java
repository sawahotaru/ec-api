package com.example.ecapi.controller;

import com.example.ecapi.dto.OrderDtos.OrderResponse;
import com.example.ecapi.dto.OrderDtos.UpdateOrderStatusRequest;
import com.example.ecapi.privacy.DemoProperties;
import com.example.ecapi.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin: Orders", description = "Order management (ADMIN only)")
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final DemoProperties demo;

    public AdminOrderController(OrderService orderService, DemoProperties demo) {
        this.orderService = orderService;
        this.demo = demo;
    }

    @Operation(summary = "List all orders (paginated)",
            description = "公開デモでは連絡先メールが伏せ字（`g***@example.com`）になる。")
    @GetMapping
    public Page<OrderResponse> list(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return orderService.findAll(pageable).map(order -> privacy(OrderResponse.from(order)));
    }

    @Operation(summary = "Get any order by id")
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(privacy(OrderResponse.from(orderService.get(id))));
    }

    @Operation(summary = "Update an order's status")
    @PatchMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateStatus(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(privacy(OrderResponse.from(orderService.updateStatus(id, request.status()))));
    }

    /**
     * 管理画面から出ていく注文はここを必ず通す。
     *
     * <p>マスクを各メソッドに直接書かず1本にまとめてあるのは、<strong>管理APIを足したときに
     * 素の {@code OrderResponse.from()} を書いてしまう</strong>のを見つけやすくするため
     * （このクラスに `from(` が裸で出てきたら漏れ）。
     */
    private OrderResponse privacy(OrderResponse response) {
        return demo.isMaskContact() ? response.masked() : response;
    }
}
