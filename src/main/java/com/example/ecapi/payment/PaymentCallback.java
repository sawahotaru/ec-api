package com.example.ecapi.payment;

/**
 * 「この注文の支払いが確定した」という、検証済みの事実。
 * {@link PaymentProvider#handleCallback} がこれを返した時点で、在庫コミットと
 * {@code OrderPaidEvent} の発行はコア側が引き受ける。
 *
 * @param orderId   支払われた注文のID
 * @param reference 決済側の参照ID（照合用）
 */
public record PaymentCallback(Long orderId, String reference) {
}
