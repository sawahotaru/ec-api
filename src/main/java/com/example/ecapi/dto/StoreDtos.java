package com.example.ecapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class StoreDtos {

    private StoreDtos() {
    }

    /**
     * ヘッダの看板に使う情報。
     *
     * @param name    店名。ロゴがあるときも捨てない（img の alt・ページタイトルに要る）
     * @param logoUrl ロゴのURL。<strong>空文字なら「ロゴなし＝店名を文字で出す」</strong>
     */
    public record BrandingResponse(String name, String logoUrl) {
    }

    /** 管理: 店名の変更。 */
    public record StoreNameRequest(@NotBlank @Size(max = 60) String name) {
    }
}
