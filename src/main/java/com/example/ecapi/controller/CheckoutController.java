package com.example.ecapi.controller;

import com.example.ecapi.dto.CheckoutDtos.QuoteLine;
import com.example.ecapi.dto.CheckoutDtos.QuoteRequest;
import com.example.ecapi.dto.CheckoutDtos.QuoteResponse;
import com.example.ecapi.dto.CheckoutDtos.ShippingConfigResponse;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.service.QuoteService;
import com.example.ecapi.service.ShippingSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Checkout (public)", description = "送料・クーポンを含む金額の見積もり")
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final QuoteService quoteService;
    private final ShippingSettings shippingSettings;

    public CheckoutController(QuoteService quoteService, ShippingSettings shippingSettings) {
        this.quoteService = quoteService;
        this.shippingSettings = shippingSettings;
    }

    @Operation(summary = "送料の公開設定",
            description = "送料と、送料無料になる金額（0 = 無料になる設定なし）。")
    @GetMapping("/shipping")
    public ShippingConfigResponse shipping() {
        return new ShippingConfigResponse(shippingSettings.fee(), shippingSettings.freeThreshold());
    }

    @Operation(summary = "金額の見積もり（在庫は動かない）",
            description = "注文時とまったく同じ計算器を通すので、ここに出た金額がそのまま請求額になる。"
                    + "クーポンが使えない場合は 400 と理由を返す。")
    @PostMapping("/quote")
    public ResponseEntity<QuoteResponse> quote(@Valid @RequestBody QuoteRequest request) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (QuoteLine line : request.items()) {
            if (quantities.merge(line.productId(), line.quantity(), Integer::sum) < 1) {
                throw new BadRequestException("Quantity must be at least 1 for product " + line.productId());
            }
        }
        return ResponseEntity.ok(QuoteResponse.from(quoteService.quote(quantities, request.couponCode())));
    }
}
