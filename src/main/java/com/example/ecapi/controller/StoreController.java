package com.example.ecapi.controller;

import com.example.ecapi.dto.StoreDtos.BrandingResponse;
import com.example.ecapi.service.BrandingSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店の見た目に関する公開情報。
 *
 * <p>ヘッダの看板は画面が最初に描くものなので、認証の要らない経路で返す。
 * ここが 401 になると、未ログインの買い手にはヘッダが空のまま見える。
 */
@Tag(name = "Store (public)", description = "店名・ロゴなどの公開情報")
@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final BrandingSettings branding;

    public StoreController(BrandingSettings branding) {
        this.branding = branding;
    }

    @Operation(summary = "店名とロゴ",
            description = "`logoUrl` が空なら、店名を文字で出す想定。相対パスなので `/ec` 配下でも解決できる。")
    @GetMapping("/branding")
    public BrandingResponse branding() {
        return new BrandingResponse(branding.name(), branding.logoUrl());
    }
}
