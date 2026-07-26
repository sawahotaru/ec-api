package com.example.ecapi.controller;

import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.dto.PaymentDtos.PaymentConfigResponse;
import com.example.ecapi.security.CurrentUserProvider;
import com.example.ecapi.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments (Stripe test mode)", description = "Stripe Checkout for orders")
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserProvider currentUserProvider;

    public PaymentController(PaymentService paymentService, CurrentUserProvider currentUserProvider) {
        this.paymentService = paymentService;
        this.currentUserProvider = currentUserProvider;
    }

    @Operation(summary = "Is Stripe enabled? (public)")
    @GetMapping("/config")
    public PaymentConfigResponse config() {
        return new PaymentConfigResponse(paymentService.isEnabled(), "test", paymentService.currency());
    }

    @Operation(summary = "Create a Stripe Checkout Session for one of my PENDING orders",
            description = "Returns checkoutUrl — open it in a browser to pay with a Stripe test card (e.g. 4242 4242 4242 4242).")
    @PostMapping("/orders/{orderId}/checkout-session")
    public CheckoutSessionResponse createSession(@PathVariable Long orderId) {
        return paymentService.createCheckoutSession(currentUserProvider.require(), orderId);
    }

    @Operation(summary = "Create a Stripe Checkout Session for a PENDING guest order",
            description = "Guest variant: authenticate with the orderToken from guest checkout instead of logging in.")
    @PostMapping("/guest/orders/{orderId}/checkout-session")
    public CheckoutSessionResponse createGuestSession(@PathVariable Long orderId,
                                                      @RequestParam String token) {
        return paymentService.createGuestCheckoutSession(orderId, token);
    }

    @Operation(summary = "Stripe webhook (public, signature-verified)",
            description = "Point your Stripe webhook here for checkout.session.completed to mark orders PAID.")
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader("Stripe-Signature") String signature) {
        paymentService.handleWebhook(payload, signature);
        return ResponseEntity.ok("ok");
    }

    @Operation(summary = "Landing page after a successful test payment (public)")
    @GetMapping(value = "/success", produces = MediaType.TEXT_HTML_VALUE)
    public String success() {
        return "<h1>Payment successful ✅</h1><p>Your test payment was received. "
                + "The order is marked PAID once Stripe delivers the webhook.</p>";
    }

    @Operation(summary = "Landing page after a cancelled test payment (public)")
    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String cancel() {
        return "<h1>Payment cancelled</h1><p>Your order is still PENDING. You can try paying again.</p>";
    }
}
