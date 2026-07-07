package com.example.ecapi.service;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.OrderRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import java.math.BigDecimal;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Stripe Checkout integration (TEST MODE). Creates a hosted Checkout Session for a
 * PENDING order and marks the order PAID when Stripe confirms via webhook.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    // Currencies with no minor unit (amount is charged as-is, not * 100).
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "jpy", "krw", "vnd", "clp", "bif", "djf", "gnf",
            "kmf", "mga", "pyg", "rwf", "ugx", "vuv", "xaf", "xof", "xpf");

    private final OrderRepository orderRepository;
    private final StripeProperties props;

    public PaymentService(OrderRepository orderRepository, StripeProperties props) {
        this.orderRepository = orderRepository;
        this.props = props;
    }

    public boolean isEnabled() {
        return StringUtils.hasText(props.getSecretKey());
    }

    public String currency() {
        return props.getCurrency();
    }

    @Transactional
    public CheckoutSessionResponse createCheckoutSession(User user, Long orderId) {
        if (!isEnabled()) {
            throw new BadRequestException("Stripe is not configured. Set STRIPE_SECRET_KEY (test key) to enable payments.");
        }
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BadRequestException("Order is not payable (status=" + order.getStatus() + ")");
        }

        RequestOptions options = RequestOptions.builder().setApiKey(props.getSecretKey()).build();
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(props.getSuccessUrl() + "?orderId=" + orderId)
                .setCancelUrl(props.getCancelUrl() + "?orderId=" + orderId)
                .setClientReferenceId(order.getId().toString())
                .putMetadata("orderId", order.getId().toString());

        for (OrderItem item : order.getItems()) {
            builder.addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity((long) item.getQuantity())
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(props.getCurrency())
                            .setUnitAmount(toMinorUnits(item.getUnitPrice(), props.getCurrency()))
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.getProductName())
                                    .build())
                            .build())
                    .build());
        }

        try {
            Session session = Session.create(builder.build(), options);
            order.setStripeSessionId(session.getId());
            orderRepository.save(order);
            return new CheckoutSessionResponse(order.getId(), session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new BadRequestException("Stripe error: " + e.getMessage());
        }
    }

    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        if (!StringUtils.hasText(props.getWebhookSecret())) {
            throw new BadRequestException("Webhook secret not configured (STRIPE_WEBHOOK_SECRET).");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, props.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new BadRequestException("Invalid Stripe signature");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
            if (object instanceof Session session) {
                String orderId = session.getMetadata() != null ? session.getMetadata().get("orderId") : null;
                if (orderId == null) {
                    orderId = session.getClientReferenceId();
                }
                if (orderId != null) {
                    markPaid(Long.valueOf(orderId));
                }
            }
        } else {
            log.debug("Ignoring Stripe event type {}", event.getType());
        }
    }

    private void markPaid(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.PAID);
                orderRepository.save(order);
                log.info("Order {} marked PAID via Stripe webhook", orderId);
            }
        });
    }

    private long toMinorUnits(BigDecimal amount, String currency) {
        if (ZERO_DECIMAL.contains(currency.toLowerCase())) {
            return amount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        }
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
