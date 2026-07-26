package com.example.ecapi.controller;

import com.example.ecapi.dto.TaxDtos.PricingModeRequest;
import com.example.ecapi.dto.TaxDtos.SettingsResponse;
import com.example.ecapi.service.SettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store-level runtime settings (ADMIN only). Currently the tax pricing mode
 * (INCLUSIVE=内税 / EXCLUSIVE=外税). Switching affects only future orders — past
 * orders keep their snapshotted mode.
 */
@Tag(name = "Admin: Settings", description = "ストア設定（税の内税/外税切替など・ADMIN限定）")
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final SettingService settingService;

    public AdminSettingsController(SettingService settingService) {
        this.settingService = settingService;
    }

    @Operation(summary = "現在のストア設定")
    @GetMapping
    public SettingsResponse get() {
        return new SettingsResponse(settingService.getPricingMode().name());
    }

    @Operation(summary = "税の表示方式を切替（INCLUSIVE=内税 / EXCLUSIVE=外税）")
    @PutMapping("/pricing-mode")
    public SettingsResponse setPricingMode(@Valid @RequestBody PricingModeRequest req) {
        return new SettingsResponse(settingService.setPricingMode(req.pricingMode()).name());
    }
}
