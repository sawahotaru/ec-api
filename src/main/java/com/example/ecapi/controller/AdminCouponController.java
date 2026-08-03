package com.example.ecapi.controller;

import com.example.ecapi.dto.CouponDtos.CouponRequest;
import com.example.ecapi.dto.CouponDtos.CouponResponse;
import com.example.ecapi.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin: Coupons", description = "クーポンの管理（ADMIN のみ）")
@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @Operation(summary = "クーポン一覧")
    @GetMapping
    public List<CouponResponse> list() {
        return couponService.findAll().stream().map(CouponResponse::from).toList();
    }

    @Operation(summary = "クーポンを作成")
    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CouponResponse.from(couponService.create(request.toEntity())));
    }

    @Operation(summary = "クーポンを更新",
            description = "利用済み回数（redeemedCount）は変更されない。")
    @PutMapping("/{id}")
    public ResponseEntity<CouponResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody CouponRequest request) {
        return ResponseEntity.ok(CouponResponse.from(couponService.update(id, request.toEntity())));
    }

    @Operation(summary = "クーポンを削除",
            description = "過去の注文には注文時点のコードと割引額がスナップショットされているため、"
                    + "削除しても金額は変わらない。")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        couponService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
