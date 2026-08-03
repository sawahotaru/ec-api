package com.example.ecapi.dto;

import com.example.ecapi.pricing.OrderPricing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    public record QuoteLine(
            @NotNull Long productId,
            @Min(1) int quantity) {
    }

    /** 見積もり要求。カートの中身とクーポンコードだけ。在庫は動かない。 */
    public record QuoteRequest(
            @NotEmpty @Valid List<QuoteLine> items,
            String couponCode) {
    }

    /**
     * 見積もり結果。<strong>注文時と同じ計算器</strong>が返すので、ここに出た数字は
     * そのまま請求額になる（在庫が減っていた・クーポンが使い切られた等で注文自体が
     * 通らないことはある）。
     */
    public record QuoteResponse(
            BigDecimal itemSubtotal,
            BigDecimal discount,
            BigDecimal shipping,
            BigDecimal shippingTax,
            BigDecimal tax,
            BigDecimal total,
            String couponCode,
            boolean freeShipping) {

        public static QuoteResponse from(OrderPricing pricing) {
            return new QuoteResponse(
                    pricing.itemSubtotal(),
                    pricing.discount(),
                    pricing.shipping(),
                    pricing.shippingTax(),
                    pricing.tax(),
                    pricing.total(),
                    pricing.couponCode(),
                    pricing.freeShipping());
        }
    }

    /** 送料の公開設定。店頭で「あと○円で送料無料」を出すために要る。 */
    public record ShippingConfigResponse(
            BigDecimal fee,
            BigDecimal freeThreshold) {
    }
}
