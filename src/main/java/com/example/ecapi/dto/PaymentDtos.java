package com.example.ecapi.dto;

import java.util.List;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * 支払い開始の結果。{@code redirectUrl} を開けば支払いに進める
     * （外部決済ページ、または銀行振込の案内ページ）。
     *
     * @param providerId 実際に使われた決済手段の id
     * @param reference  決済側の参照ID（Stripe の Session id、振込の照合番号）
     */
    public record CheckoutSessionResponse(Long orderId, String providerId,
                                          String reference, String redirectUrl) {
    }

    /** 購入画面に出す決済手段の選択肢。 */
    public record PaymentProviderInfo(String id, String displayName) {
    }

    /**
     * 決済が使えるかをクライアントに伝える公開情報。
     * {@code enabled} は「有効な決済手段が1つ以上あるか」。既存クライアントとの互換のため残している。
     */
    public record PaymentConfigResponse(boolean enabled, String mode, String currency,
                                        List<PaymentProviderInfo> providers) {
    }
}
