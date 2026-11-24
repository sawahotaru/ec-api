package com.example.ecapi.payment;

/**
 * 「この注文の支払いが確定した」という、検証済みの事実。
 * {@link PaymentProvider#handleCallback} がこれを返した後、金額・通貨・入金確定の照合と、
 * 在庫コミット・{@code OrderPaidEvent} の発行はコア側が引き受ける。
 *
 * @param orderId     支払われた注文のID
 * @param reference   決済側の参照ID（照合用）
 * @param amountMinor 決済側が受け取った金額。補助単位の整数（円なら円、ドルならセント）。
 *                    必須（null は照合失敗として扱う）
 * @param currency    通貨コード（ISO 4217）。必須（null は照合失敗として扱う）
 * @param paid        入金が確定しているか
 * @param eventId     通知の ID（ログ用。照合には使わない）
 */
public record PaymentCallback(Long orderId, String reference, Long amountMinor,
                              String currency, boolean paid, String eventId) {
}
