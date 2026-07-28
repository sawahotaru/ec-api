package com.example.ecapi.controller;

import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.dto.PaymentDtos.PaymentConfigResponse;
import com.example.ecapi.security.CurrentUserProvider;
import com.example.ecapi.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "決済手段プラグイン（Stripe / 銀行振込 …）経由の支払い")
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserProvider currentUserProvider;

    public PaymentController(PaymentService paymentService, CurrentUserProvider currentUserProvider) {
        this.paymentService = paymentService;
        this.currentUserProvider = currentUserProvider;
    }

    @Operation(summary = "利用できる決済手段の一覧（公開）")
    @GetMapping("/config")
    public PaymentConfigResponse config() {
        return new PaymentConfigResponse(paymentService.isEnabled(), "test",
                paymentService.currency(), paymentService.providers());
    }

    @Operation(summary = "自分の PENDING 注文の支払いを開始する",
            description = "redirectUrl を開くと支払いに進む。provider 省略時は既定の決済手段。")
    @PostMapping("/orders/{orderId}/checkout-session")
    public CheckoutSessionResponse createSession(@PathVariable Long orderId,
                                                 @RequestParam(required = false) String provider) {
        return paymentService.createCheckoutSession(currentUserProvider.require(), orderId, provider);
    }

    @Operation(summary = "ゲスト注文の支払いを開始する",
            description = "ログインの代わりに、ゲストチェックアウトで受け取った orderToken で認証する。")
    @PostMapping("/guest/orders/{orderId}/checkout-session")
    public CheckoutSessionResponse createGuestSession(@PathVariable Long orderId,
                                                      @RequestParam String token,
                                                      @RequestParam(required = false) String provider) {
        return paymentService.createGuestCheckoutSession(orderId, token, provider);
    }

    /**
     * 決済手段ごとの支払確定通知の受け口（公開・署名検証あり）。
     * providerId をパスに持つので、決済手段が増えても<strong>このメソッドは変わらない</strong>。
     */
    @Operation(summary = "決済手段のコールバック / Webhook（公開・署名検証あり）")
    @PostMapping("/{providerId}/webhook")
    public ResponseEntity<String> webhook(@PathVariable String providerId,
                                          @RequestBody String payload,
                                          HttpServletRequest request) {
        paymentService.handleCallback(providerId, payload, headersOf(request));
        return ResponseEntity.ok("ok");
    }

    /**
     * 旧 Stripe Webhook URL。<strong>Stripe ダッシュボードに既に登録されている</strong>ため
     * 互換のために残してある（新規は {@code /api/payments/stripe/webhook} を使うこと）。
     */
    @Operation(summary = "[非推奨] 旧 Stripe Webhook URL", deprecated = true)
    @PostMapping("/webhook")
    public ResponseEntity<String> legacyStripeWebhook(@RequestBody String payload,
                                                      HttpServletRequest request) {
        paymentService.handleCallback("stripe", payload, headersOf(request));
        return ResponseEntity.ok("ok");
    }

    @Operation(summary = "外部決済ページを持たない手段の案内ページ（銀行振込の振込先など）",
            description = "会員はログイン、ゲストは token が必要。")
    @GetMapping(value = "/{providerId}/instructions", produces = MediaType.TEXT_HTML_VALUE)
    public String instructions(@PathVariable String providerId,
                               @RequestParam Long orderId,
                               @RequestParam(required = false) String token) {
        return paymentService.instructions(providerId, orderId, token,
                token == null || token.isBlank() ? currentUserProvider.require() : null);
    }

    @Operation(summary = "支払い成功後の着地ページ（公開）")
    @GetMapping(value = "/success", produces = MediaType.TEXT_HTML_VALUE)
    public String success() {
        return "<h1>Payment successful ✅</h1><p>Your test payment was received. "
                + "The order is marked PAID once the provider delivers its callback.</p>";
    }

    @Operation(summary = "支払いキャンセル後の着地ページ（公開）")
    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String cancel() {
        return "<h1>Payment cancelled</h1><p>Your order is still PENDING. You can try paying again.</p>";
    }

    /**
     * ヘッダを大文字小文字を区別しないマップに写す。署名ヘッダ名の綴り
     * （{@code Stripe-Signature} / {@code stripe-signature}）に決済手段の実装が
     * 振り回されないようにするため。
     */
    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Collections.emptyMap();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
