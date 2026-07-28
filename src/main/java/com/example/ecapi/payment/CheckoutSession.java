package com.example.ecapi.payment;

/**
 * {@link PaymentProvider#createSession} の戻り値。
 *
 * @param reference   決済側での参照ID。注文の {@code paymentReference} に保存され、
 *                    後から突き合わせ（照会・返金・問い合わせ）に使う。
 *                    Stripe なら Checkout Session id、銀行振込なら振込用の照合番号。
 * @param redirectUrl 利用者を送る先。外部決済ならホスト決済ページ、
 *                    銀行振込なら振込先案内ページ。
 */
public record CheckoutSession(String reference, String redirectUrl) {
}
