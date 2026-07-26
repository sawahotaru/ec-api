package com.example.ecapi.controller;

import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.dto.TaxDtos.TaxConfigResponse;
import com.example.ecapi.dto.TaxDtos.TaxConfigResponse.CurrentRate;
import com.example.ecapi.service.TaxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: lets the storefront know the pricing mode (内税/外税) and current rates. */
@Tag(name = "Tax", description = "消費税の表示設定（公開）")
@RestController
@RequestMapping("/api/tax")
public class TaxController {

    private final TaxService taxService;

    public TaxController(TaxService taxService) {
        this.taxService = taxService;
    }

    @Operation(summary = "税の表示方式と現行税率（公開）")
    @GetMapping("/config")
    public TaxConfigResponse config() {
        LocalDate today = LocalDate.now();
        List<CurrentRate> rates = List.of(
                new CurrentRate(TaxCategory.STANDARD.name(), taxService.rateFor(TaxCategory.STANDARD, today)),
                new CurrentRate(TaxCategory.REDUCED.name(), taxService.rateFor(TaxCategory.REDUCED, today)));
        return new TaxConfigResponse(taxService.pricingMode().name(), rates);
    }
}
