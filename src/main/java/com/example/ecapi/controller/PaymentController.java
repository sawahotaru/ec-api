package com.example.ecapi.controller;

import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.dto.PaymentDtos.PaymentConfigResponse;
import com.example.ecapi.payment.PaymentInstructions;
import com.example.ecapi.security.ClientKey;
import com.example.ecapi.security.CurrentUserProvider;
import com.example.ecapi.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

    /** 戻り先の注文番号。先頭が 0 でない数字 1〜18 桁だけを有効とする。 */
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^[1-9][0-9]{0,17}$");

    private final PaymentService paymentService;
    private final CurrentUserProvider currentUserProvider;
    private final String contextPath;

    public PaymentController(PaymentService paymentService, CurrentUserProvider currentUserProvider,
                             @Value("${server.servlet.context-path:}") String contextPath) {
        this.paymentService = paymentService;
        this.currentUserProvider = currentUserProvider;
        this.contextPath = contextPath;
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
                                                 @RequestParam(required = false) String provider,
                                                 HttpServletRequest request) {
        return paymentService.createCheckoutSession(currentUserProvider.require(), orderId, provider,
                ClientKey.of(request));
    }

    @Operation(summary = "ゲスト注文の支払いを開始する",
            description = "ログインの代わりに、ゲストチェックアウトで受け取った orderToken で認証する。")
    @PostMapping("/guest/orders/{orderId}/checkout-session")
    public CheckoutSessionResponse createGuestSession(@PathVariable Long orderId,
                                                      @RequestParam String token,
                                                      @RequestParam(required = false) String provider,
                                                      HttpServletRequest request) {
        return paymentService.createGuestCheckoutSession(orderId, token, provider, ClientKey.of(request));
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

    @Operation(summary = "外部決済ページを持たない手段の案内（銀行振込の振込先など。JSON）",
            description = "会員はログイン、ゲストは token が必要。その手段を選んだお支払い待ちの注文だけ。")
    @GetMapping(value = "/{providerId}/instructions", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaymentInstructions instructions(@PathVariable String providerId,
                               @RequestParam Long orderId,
                               @RequestParam(required = false) String token) {
        return paymentService.instructions(providerId, orderId, token,
                token == null || token.isBlank() ? currentUserProvider.require() : null);
    }

    @Operation(summary = "旧い戻り先（ストアへ転送）")
    @GetMapping("/success")
    public ResponseEntity<Void> success(@RequestParam(required = false) String orderId) {
        return returnToStore("success", orderId);
    }

    @Operation(summary = "旧い戻り先（ストアへ転送）")
    @GetMapping("/cancel")
    public ResponseEntity<Void> cancel(@RequestParam(required = false) String orderId) {
        return returnToStore("cancel", orderId);
    }

    /**
     * 旧い戻り先から、ストアのトップへ 302 で転送する。本文は返さず、DB は読まない。
     * 注文番号は数字だけ（18 桁まで）のときに限って付け、それ以外は使わない・反射しない。
     */
    private ResponseEntity<Void> returnToStore(String kind, String orderId) {
        String target = contextPath + "/?payment=" + kind;
        if (orderId != null && ORDER_ID_PATTERN.matcher(orderId).matches()) {
            target += "&orderId=" + orderId;
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
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
