package com.example.ecapi.controller;

import com.example.ecapi.dto.StoreDtos.BrandingResponse;
import com.example.ecapi.dto.StoreDtos.StoreNameRequest;
import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.service.BrandingSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 店名とロゴの変更（ADMIN のみ）。
 *
 * <p>ロゴの保存は商品画像と<strong>同じ {@link ProductImageStorage} を通す</strong>。
 * 用途ごとに保存処理を書き分けると、そのうちどれかが先頭バイトの判定や SVG の拒否を
 * 落としたまま増えていく——しかも通常の操作では気づけない。
 */
@Tag(name = "Admin: Branding", description = "店名・ロゴの変更（ADMIN のみ）")
@RestController
@RequestMapping("/api/admin/branding")
public class AdminBrandingController {

    /** 生成するファイル名の先頭。利用者の入力は混ぜない（パストラバーサルの材料を作らない）。 */
    private static final String LOGO_PREFIX = "logo";

    private final BrandingSettings branding;
    private final ProductImageStorage storage;

    public AdminBrandingController(BrandingSettings branding, ProductImageStorage storage) {
        this.branding = branding;
        this.storage = storage;
    }

    @Operation(summary = "店名を変更",
            description = "ロゴを設定していても店名は使う（img の alt・ページタイトル）。")
    @PutMapping("/name")
    public BrandingResponse setName(@Valid @RequestBody StoreNameRequest request) {
        branding.setName(request.name());
        return current();
    }

    @Operation(summary = "ロゴ画像をアップロード",
            description = "multipart/form-data の `file`。**JPEG / PNG / WebP のみ**で、"
                    + "拡張子や Content-Type ではなく先頭バイトで判定する。"
                    + "⚠ 同梱の既定ロゴは SVG だが、**アップロードでは SVG を受け付けない**"
                    + "（同梱物は jar の中身だが、アップロードは自サイトのオリジンから配信する"
                    + "利用者の入力＝スクリプトを埋めた SVG が保存型XSSになるため）。")
    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BrandingResponse> uploadLogo(@RequestParam("file") MultipartFile file) {
        if (!storage.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String previous = branding.logoUrl();
        branding.setLogoUrl(storage.store(file, LOGO_PREFIX));
        storage.deleteIfUploaded(previous);   // 差し替え前のロゴは参照されなくなる
        return ResponseEntity.ok(current());
    }

    @Operation(summary = "ロゴを外す（店名を文字で表示する）",
            description = "アップロードしたロゴなら実体ファイルも消える。"
                    + "同梱ロゴを指していた場合はファイルには触らない（jar の中身なので）。")
    @DeleteMapping("/logo")
    public BrandingResponse removeLogo() {
        String previous = branding.logoUrl();
        branding.setLogoUrl("");
        storage.deleteIfUploaded(previous);
        return current();
    }

    private BrandingResponse current() {
        return new BrandingResponse(branding.name(), branding.logoUrl());
    }
}
