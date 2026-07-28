package com.example.ecapi.service;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.dto.PaymentDtos.PaymentProviderInfo;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.payment.CheckoutSession;
import com.example.ecapi.payment.PaymentProperties;
import com.example.ecapi.payment.PaymentProvider;
import com.example.ecapi.payment.PaymentProviderRegistry;
import com.example.ecapi.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 決済の<em>手順</em>だけを持つ調停役。「どの業者にどう繋ぐか」は
 * {@link PaymentProvider} の実装側にあり、このクラスに決済業者のSDKは一切入らない。
 *
 * <p>ここに残る責務は決済手段によらず不変な3つ:
 * <ol>
 *   <li>注文の所有者確認（ログイン or ゲストトークン）</li>
 *   <li>支払い可能な状態か（PENDING か）の検証</li>
 *   <li>支払い確定時の在庫コミットとイベント発行（{@link OrderService#markPaid} へ委譲）</li>
 * </ol>
 */
@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentProviderRegistry registry;
    private final PaymentProperties properties;

    public PaymentService(OrderRepository orderRepository,
                          OrderService orderService,
                          PaymentProviderRegistry registry,
                          PaymentProperties properties) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.registry = registry;
        this.properties = properties;
    }

    /** 決済手段が1つでも使える状態か。フロントの「支払う」ボタン表示に使う。 */
    public boolean isEnabled() {
        return registry.anyEnabled();
    }

    public String currency() {
        return properties.getCurrency();
    }

    /** 購入画面に出す選択肢。 */
    public List<PaymentProviderInfo> providers() {
        return registry.enabled().stream()
                .map(p -> new PaymentProviderInfo(p.id(), p.displayName()))
                .toList();
    }

    @Transactional
    public CheckoutSessionResponse createCheckoutSession(User user, Long orderId, String providerId) {
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        return createSessionFor(order, providerId);
    }

    /** ゲスト版: ログインの代わりに注文トークンでリクエストを認証する。 */
    @Transactional
    public CheckoutSessionResponse createGuestCheckoutSession(Long orderId, String token, String providerId) {
        return createSessionFor(requireGuestOrder(orderId, token), providerId);
    }

    private CheckoutSessionResponse createSessionFor(Order order, String providerId) {
        PaymentProvider provider = registry.require(providerId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BadRequestException("Order is not payable (status=" + order.getStatus() + ")");
        }

        CheckoutSession session = provider.createSession(order);
        order.setPaymentProvider(provider.id());
        order.setPaymentReference(session.reference());
        orderRepository.save(order);
        return new CheckoutSessionResponse(order.getId(), provider.id(),
                session.reference(), session.redirectUrl());
    }

    /**
     * 決済側からの支払確定通知。署名検証は {@link PaymentProvider#handleCallback} の中で
     * 完結しており、このメソッドは業者ごとの差異を知らない。
     */
    @Transactional
    public void handleCallback(String providerId, String payload, Map<String, String> headers) {
        PaymentProvider provider = registry.require(providerId);
        provider.handleCallback(payload, headers)
                .ifPresent(callback -> orderService.markPaid(
                        callback.orderId(), provider.id(), callback.reference()));
    }

    /**
     * 外部決済ページを持たない手段（銀行振込等）の案内ページ。閲覧権限は注文の閲覧権限と
     * 同じ——金額と照合番号が載るため、ログインかゲストトークンを要求する。
     */
    @Transactional(readOnly = true)
    public String instructions(String providerId, Long orderId, String token, User user) {
        PaymentProvider provider = registry.require(providerId);
        Order order = (token != null && !token.isBlank())
                ? requireGuestOrder(orderId, token)
                : orderRepository.findByIdAndUserId(orderId, requireUser(user).getId())
                        .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        return provider.instructionsHtml(order)
                .orElseThrow(() -> new BadRequestException(
                        "Payment provider '" + provider.id() + "' has no instructions page"));
    }

    private Order requireGuestOrder(Long orderId, String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Order token is required");
        }
        return orderRepository.findByIdAndOrderToken(orderId, token)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    private User requireUser(User user) {
        if (user == null) {
            throw new BadRequestException("Login or an order token is required");
        }
        return user;
    }
}
