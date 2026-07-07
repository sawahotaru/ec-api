package com.example.ecapi.dto;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** Returned when a Stripe Checkout Session is created; open checkoutUrl in a browser. */
    public record CheckoutSessionResponse(Long orderId, String sessionId, String checkoutUrl) {
    }

    /** Public flag so a client can tell whether payments are enabled. */
    public record PaymentConfigResponse(boolean enabled, String mode, String currency) {
    }
}
