package com.example.ecapi.controller;

import com.example.ecapi.dto.TaxDtos.PricingModeRequest;
import com.example.ecapi.dto.TaxDtos.SettingsResponse;
import com.example.ecapi.dto.TaxDtos.ShippingSettingsRequest;
import com.example.ecapi.privacy.DemoProperties;
import com.example.ecapi.service.SettingService;
import com.example.ecapi.service.ShippingSettings;
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
    private final ShippingSettings shippingSettings;
    private final DemoProperties demo;

    public AdminSettingsController(SettingService settingService, ShippingSettings shippingSettings,
                                   DemoProperties demo) {
        this.settingService = settingService;
        this.shippingSettings = shippingSettings;
        this.demo = demo;
    }

    @Operation(summary = "現在のストア設定")
    @GetMapping
    public SettingsResponse get() {
        return current();
    }

    @Operation(summary = "税の表示方式を切替（INCLUSIVE=内税 / EXCLUSIVE=外税）")
    @PutMapping("/pricing-mode")
    public SettingsResponse setPricingMode(@Valid @RequestBody PricingModeRequest req) {
        settingService.setPricingMode(req.pricingMode());
        return current();
    }

    @Operation(summary = "送料の設定",
            description = "金額は商品価格と同じ流儀（内税モードなら税込・外税モードなら税抜）。"
                    + "freeThreshold は**割引後**の商品合計で判定する。0 なら送料無料にはならない。")
    @PutMapping("/shipping")
    public SettingsResponse setShipping(@Valid @RequestBody ShippingSettingsRequest req) {
        shippingSettings.setFee(req.fee());
        shippingSettings.setFreeThreshold(req.freeThreshold());
        return current();
    }

    private SettingsResponse current() {
        return new SettingsResponse(
                settingService.getPricingMode().name(),
                shippingSettings.fee(),
                shippingSettings.freeThreshold(),
                demo.isReadOnly());
    }
}
