package com.example.ecapi.payment.provider;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.payment.CheckoutSession;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 補助単位を持たない通貨（金額を100倍せずそのまま渡す）。 */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "jpy", "krw", "vnd", "clp", "bif", "djf", "gnf",
            "kmf", "mga", "pyg", "rwf", "ugx", "vuv", "xaf", "xof", "xpf");

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
        return StringUtils.hasText(props.getSecretKey());
    }

    @Override
    public CheckoutSession createSession(Order order) {
        if (!isEnabled()) {
            throw new BadRequestException(
                    "Stripe is not configured. Set STRIPE_SECRET_KEY (test key) to enable payments.");
        }
        String currency = paymentProps.getCurrency();
        RequestOptions options = RequestOptions.builder().setApiKey(props.getSecretKey()).build();
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(props.getSuccessUrl() + "?orderId=" + order.getId())
                .setCancelUrl(props.getCancelUrl() + "?orderId=" + order.getId())
                .setClientReferenceId(order.getId().toString())
                .putMetadata("orderId", order.getId().toString());

        for (OrderItem item : order.getItems()) {
            builder.addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity((long) item.getQuantity())
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(currency)
                            .setUnitAmount(toMinorUnits(item.getUnitPrice(), currency))
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.getProductName())
                                    .build())
                            .build())
                    .build());
        }

        try {
            Session session = Session.create(builder.build(), options);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new BadRequestException("Stripe error: " + e.getMessage());
        }
    }

    @Override
    public Optional<PaymentCallback> handleCallback(String payload, Map<String, String> headers) {
        if (!StringUtils.hasText(props.getWebhookSecret())) {
            throw new BadRequestException("Webhook secret not configured (STRIPE_WEBHOOK_SECRET).");
        }
        String signature = headers.get("Stripe-Signature");
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, props.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new BadRequestException("Invalid Stripe signature");
        }

        if (!"checkout.session.completed".equals(event.getType())) {
            log.debug("Ignoring Stripe event type {}", event.getType());
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
                log.warn("Could not deserialize checkout.session.completed payload: {}", e.getMessage());
            }
        }
        if (!(object instanceof Session session)) {
            return Optional.empty();
        }

        String orderId = session.getMetadata() != null ? session.getMetadata().get("orderId") : null;
        if (orderId == null) {
            orderId = session.getClientReferenceId();
        }
        if (orderId == null) {
            log.warn("Stripe session {} carried no orderId — cannot mark an order paid", session.getId());
            return Optional.empty();
        }
        return Optional.of(new PaymentCallback(Long.valueOf(orderId), session.getId()));
    }

    private long toMinorUnits(BigDecimal amount, String currency) {
        if (ZERO_DECIMAL.contains(currency.toLowerCase())) {
            return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
