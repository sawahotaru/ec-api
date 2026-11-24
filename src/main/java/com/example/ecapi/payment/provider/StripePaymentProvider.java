package com.example.ecapi.payment.provider;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.PaymentCallbackUnreadableException;
import com.example.ecapi.payment.CheckoutSession;
import com.example.ecapi.payment.MinorUnits;
import com.example.ecapi.payment.PaymentCallback;
import com.example.ecapi.payment.PaymentProperties;
import com.example.ecapi.payment.PaymentProvider;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stripe Checkout（テストモード）による決済。{@link PaymentProvider} の第1実装で、
 * 以前 {@code PaymentService} に直書きされていた Stripe 固有処理は<strong>すべてここに
 * 閉じ込めてある</strong>——Stripe SDK の import がこのクラスの外に出ていないことが、
 * 抽象が漏れていないことの確認になる。
 */
@Component
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);

    /** 支払い開始に失敗したときの固定文（設定の不足・Stripe のエラーを外に区別させない）。 */
    static final String START_FAILED = "決済を開始できませんでした。時間をおいてお試しください。";
    /** Webhook の要求を受け付けなかったときの固定文（署名の無し・不正を区別させない）。 */
    static final String REQUEST_FAILED = "要求を処理できませんでした";

    private final StripeProperties props;
    private final PaymentProperties paymentProps;

    public StripePaymentProvider(StripeProperties props, PaymentProperties paymentProps) {
        this.props = props;
        this.paymentProps = paymentProps;
    }

    @Override
    public String id() {
        return "stripe";
    }

    @Override
    public String displayName() {
        return "クレジットカード (Stripe)";
    }

    @Override
    public boolean isEnabled() {
        // テスト用のキーだけを有効にする。本番用などそれ以外のキーでは Stripe を使わない。
        String key = props.getSecretKey();
        return StringUtils.hasText(key) && (key.startsWith("sk_test_") || key.startsWith("rk_test_"));
    }

    /**
     * 起動完了後に一度だけ、設定の不整合を知らせる（起動は止めない）。
     * キーの値・接頭辞・長さはログに出さない。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportConfigOnStartup() {
        if (StringUtils.hasText(props.getSecretKey()) && !isEnabled()) {
            log.error("STRIPE_KEY_NOT_TEST: Stripe は無効です（テスト用のキーではありません）");
        }
        String whsec = props.getWebhookSecret();
        if (StringUtils.hasText(whsec) && !whsec.startsWith("whsec_")) {
            log.warn("STRIPE_WEBHOOK_SECRET_FORMAT: Webhook の署名シークレットの形式が想定と異なります");
        }
    }

    @Override
    public CheckoutSession createSession(Order order) {
        if (!isEnabled()) {
            throw new BadRequestException(START_FAILED);
        }
        String currency = paymentProps.getCurrency();
        RequestOptions options = RequestOptions.builder().setApiKey(props.getSecretKey()).build();
        SessionCreateParams params = buildParams(order, currency);

        try {
            Session session = Session.create(params, options);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw clientError(e, order.getId());
        }
    }

    /**
     * Stripe API の失敗を、利用者に返す固定文へ写し替える。例外の文言（キーの断片や
     * 要求パラメータ名が入りうる）は応答にもログにも出さない。原因の追跡は Stripe 側の要求 ID で行う。
     */
    static BadRequestException clientError(StripeException e, Long orderId) {
        log.warn("PAYMENT_PROVIDER_ERROR type={} code={} status={} request={} order={}",
                e.getClass().getSimpleName(), e.getCode(), e.getStatusCode(), e.getRequestId(), orderId);
        return new BadRequestException(START_FAILED);
    }

    /**
     * Checkout セッションの作成パラメータを組み立てる（Stripe へは送らない）。
     *
     * <p>請求は<strong>注文の合計（{@code order.getTotalAmount()}）を 1 行</strong>で出す。
     * 商品ごとの {@code unitPrice × quantity} を並べると、割引・送料・外税の税額が
     * 抜け落ちて請求額が注文の合計と食い違う。内訳は説明欄に文字で添える。
     */
    SessionCreateParams buildParams(Order order, String currency) {
        long amount = MinorUnits.of(order.getTotalAmount(), currency);
        if (amount <= 0) {
            throw new BadRequestException("この注文はカードでお支払いいただけません");
        }

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(props.getSuccessUrl() + "?orderId=" + order.getId())
                .setCancelUrl(props.getCancelUrl() + "?orderId=" + order.getId())
                .setClientReferenceId(order.getId().toString())
                .putMetadata("orderId", order.getId().toString());

        builder.addLineItem(SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency)
                        .setUnitAmount(amount)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName("ご注文 #" + order.getId())
                                .setDescription(describe(order))
                                .build())
                        .build())
                .build());
        return builder.build();
    }

    /** 品目の説明欄。商品名と数量だけ（メールアドレス・トークンは入れない）。 */
    private static String describe(Order order) {
        List<OrderItem> items = order.getItems();
        if (items.isEmpty()) {
            return "送料・割引・税を含む合計";
        }
        OrderItem first = items.get(0);
        StringBuilder sb = new StringBuilder(first.getProductName())
                .append(" ×").append(first.getQuantity());
        if (items.size() > 1) {
            sb.append(" ほか ").append(items.size() - 1).append(" 種");
        }
        return sb.append("（送料・割引・税を含む合計）").toString();
    }

    @Override
    public Optional<PaymentCallback> handleCallback(String payload, Map<String, String> headers) {
        if (!StringUtils.hasText(props.getWebhookSecret())) {
            throw new BadRequestException(REQUEST_FAILED);
        }
        String signature = headers.get("Stripe-Signature");
        // ヘッダが無いと SDK 内部で NullPointerException になり 500 を返していた
        // （拒否はできていたが、署名なしの雑な POST だけでスタックトレースがログに積もる）。
        if (!StringUtils.hasText(signature)) {
            throw new BadRequestException(REQUEST_FAILED);
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, props.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new BadRequestException(REQUEST_FAILED);
        }

        if (!"checkout.session.completed".equals(event.getType())) {
            log.debug("Ignoring Stripe event type {}", event.getType());
            return Optional.empty();
        }

        // 本番モードのイベントは、テスト用の運用では受理しない（PAID にしない。200 を返して再送もさせない）
        if (Boolean.TRUE.equals(event.getLivemode())) {
            log.error("PAYMENT_LIVEMODE_EVENT event={}", event.getId());
            return Optional.empty();
        }

        // getObject() は、イベントのAPIバージョンがSDKの固定バージョンと違うと空になる
        // （Stripeアカウントは別バージョンを送ってくることが多い）。バージョン差で決済が
        // 黙って取りこぼされないよう deserializeUnsafe() にフォールバックする。
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject object = deserializer.getObject().orElse(null);
        if (object == null) {
            try {
                object = deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                // 5xx で返して Stripe に再送させる（2xx だと再送されず入金を取りこぼす）
                throw new PaymentCallbackUnreadableException(event.getId(), e.getClass().getSimpleName());
            }
        }
        if (!(object instanceof Session session)) {
            throw new PaymentCallbackUnreadableException(event.getId(),
                    object == null ? "null" : object.getClass().getSimpleName());
        }

        String orderId = session.getMetadata() != null ? session.getMetadata().get("orderId") : null;
        if (orderId == null) {
            orderId = session.getClientReferenceId();
        }
        if (orderId == null) {
            log.warn("Stripe session {} carried no orderId — cannot mark an order paid", session.getId());
            return Optional.empty();
        }
        log.info("PAYMENT_EVENT event={} order={} session={}", event.getId(), orderId, session.getId());
        return Optional.of(new PaymentCallback(Long.valueOf(orderId), session.getId(),
                session.getAmountTotal(), session.getCurrency(),
                "paid".equals(session.getPaymentStatus()), event.getId()));
    }
}
