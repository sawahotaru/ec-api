package com.example.ecapi.service;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.PaymentDtos.CheckoutSessionResponse;
import com.example.ecapi.dto.PaymentDtos.PaymentProviderInfo;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.payment.CheckoutSession;
import com.example.ecapi.payment.ExpireResult;
import com.example.ecapi.payment.PaymentInstructions;
import com.example.ecapi.payment.PaymentProperties;
import com.example.ecapi.payment.PaymentProvider;
import com.example.ecapi.payment.PaymentProviderRegistry;
import com.example.ecapi.payment.provider.StripePaymentProvider;
import com.example.ecapi.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
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

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final String PAYMENT_BEING_CONFIRMED =
            "お支払いを確認しています。少し待ってから注文の状態をご確認ください";
    static final String CARD_WINDOW_CLOSED = "カードでのお支払いの期限を過ぎています。お振込でお支払いください";
    static final String STATE_CHANGED = "ご注文の状態が変わりました。注文の状態をご確認ください";

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentProviderRegistry registry;
    private final PaymentProperties properties;
    private final PaymentDeadlines deadlines;
    private final BankTransferGuard bankGuard;

    public PaymentService(OrderRepository orderRepository,
                          OrderService orderService,
                          PaymentProviderRegistry registry,
                          PaymentProperties properties,
                          PaymentDeadlines deadlines,
                          BankTransferGuard bankGuard) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.registry = registry;
        this.properties = properties;
        this.deadlines = deadlines;
        this.bankGuard = bankGuard;
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

    /**
     * @param clientKey 送信元（銀行振込の回数の抑え 送信元の回数 に使う）。要求の外から呼ぶとき（テストなど）は null で、送信元の回数 は数えない。
     */
    @Transactional
    public CheckoutSessionResponse createCheckoutSession(User user, Long orderId, String providerId,
                                                         String clientKey) {
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        return createSessionFor(order, providerId, clientKey);
    }

    /** ゲスト版: ログインの代わりに注文トークンでリクエストを認証する。 */
    @Transactional
    public CheckoutSessionResponse createGuestCheckoutSession(Long orderId, String token, String providerId,
                                                              String clientKey) {
        return createSessionFor(requireGuestOrder(orderId, token), providerId, clientKey);
    }

    /**
     * 支払いを始める（または始め直す）。順序は固定:
     * <ol>
     *   <li>本人確認と PENDING の確認（ロックなし）。前の決済手段と参照を控える。読んだ実体には値を書かない
     *       （書くとコミットで古い状態ごと書き戻され、取り消し・期限切れ・入金を PENDING で上書きしうる）。</li>
     *   <li>同じ注文で振込を選び直したときは、案内だけを返して終わる（冪等。失効も抑えも呼ばない）。</li>
     *   <li>前の決済ページの失効（外部。DB のロックを取る前）。もう払われていれば新しく作らない。</li>
     *   <li>振込なら抑え 送信元の回数 → 会員の件数 → 商品ごとの上限（商品ごとの上限 だけが商品の行をロックする）。カードなら新しい決済ページの作成（外部）。</li>
     *   <li>条件付きの更新で決済手段と参照を書く。0 件なら、外部を呼んでいる間に状態か参照が変わったので、
     *       作ったページを失効させて 409。</li>
     * </ol>
     */
    private CheckoutSessionResponse createSessionFor(Order order, String providerId, String clientKey) {
        try {
            return startPayment(order, providerId, clientKey);
        } catch (PessimisticLockingFailureException e) {
            // デッドロック・ロック待ちの時間切れ（商品の行の集計・条件付きの更新を含む）。再試行を促す 409 にする
            throw new ConflictException(BankTransferGuard.BUSY_MESSAGE);
        }
    }

    private CheckoutSessionResponse startPayment(Order order, String providerId, String clientKey) {
        PaymentProvider provider = registry.require(providerId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BadRequestException("Order is not payable (status=" + order.getStatus() + ")");
        }
        Long orderId = order.getId();
        String previousProviderId = order.getPaymentProvider();
        String previousReference = order.getPaymentReference() == null ? "" : order.getPaymentReference();
        boolean bank = PaymentDeadlines.BANK_TRANSFER.equals(provider.id());
        boolean previousBank = PaymentDeadlines.BANK_TRANSFER.equals(previousProviderId);

        if (bank && previousBank) {
            // 選び直し: 照合番号は注文 ID から決まる。何も書かず、同じ案内を返す。
            CheckoutSession again = provider.createSession(order);
            return new CheckoutSessionResponse(orderId, provider.id(), again.reference(),
                    again.redirectUrl(), provider.instructions(order).orElse(null));
        }
        if (!bank && previousBank && deadlines.cardWindowClosed(order)) {
            // 作成から保留の分数を過ぎた振込の注文は、カードを始めても掃除ですぐ期限切れになる
            throw new ConflictException(CARD_WINDOW_CLOSED);
        }

        expirePreviousPage(order, previousProviderId);

        if (bank) {
            bankGuard.checkSource(clientKey);
            bankGuard.checkMember(order);
            bankGuard.checkStock(order);
        }
        CheckoutSession session = provider.createSession(order);

        int updated = orderRepository.updatePaymentIfPending(
                orderId, provider.id(), session.reference(), previousReference);
        if (updated == 0) {
            // 作ったページを残さない（結果は警告に残すだけ）
            Order probe = new Order();
            probe.setId(orderId);
            probe.setPaymentProvider(provider.id());
            probe.setPaymentReference(session.reference());
            if (provider.expireOpenPayment(probe) == ExpireResult.FAILED) {
                log.warn("PAYMENT_EXPIRE_FAILED order={} session={}", orderId, session.reference());
            }
            throw new ConflictException(STATE_CHANGED);
        }
        return new CheckoutSessionResponse(orderId, provider.id(), session.reference(),
                session.redirectUrl(), provider.instructions(order).orElse(null));
    }

    /** 前の決済ページを失効させる。もう払われている・分からないときは新しく始めない。 */
    private void expirePreviousPage(Order order, String previousProviderId) {
        PaymentProvider previous = registry.find(previousProviderId).orElse(null);
        if (previous == null) {
            return;
        }
        ExpireResult result = previous.expireOpenPayment(order);
        if (result == ExpireResult.ALREADY_COMPLETED) {
            throw new ConflictException(PAYMENT_BEING_CONFIRMED);
        }
        if (result == ExpireResult.FAILED) {
            throw new BadRequestException(StripePaymentProvider.START_FAILED);
        }
    }

    /**
     * 決済側からの支払確定通知。署名検証は {@link PaymentProvider#handleCallback} の中で
     * 完結しており、このメソッドは業者ごとの差異を知らない。
     */
    @Transactional
    public void handleCallback(String providerId, String payload, Map<String, String> headers) {
        PaymentProvider provider = registry.require(providerId);
        provider.handleCallback(payload, headers)
                .ifPresent(callback -> orderService.confirmPayment(
                        callback, provider.id(), properties.getCurrency()));
    }

    /**
     * 外部決済ページを持たない手段（銀行振込等）の案内（JSON）。閲覧権限は注文の閲覧権限と
     * 同じ——金額と照合番号が載るため、ログインかゲストトークンを要求する。
     * その手段を選んだ PENDING の注文だけに返し、それ以外は 404。
     */
    @Transactional(readOnly = true)
    public PaymentInstructions instructions(String providerId, Long orderId, String token, User user) {
        PaymentProvider provider = registry.require(providerId);
        Order order = (token != null && !token.isBlank())
                ? requireGuestOrder(orderId, token)
                : orderRepository.findByIdAndUserId(orderId, requireUser(user).getId())
                        .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PENDING || !provider.id().equals(order.getPaymentProvider())) {
            throw new NotFoundException("Order not found: " + orderId);
        }
        return provider.instructions(order)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
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
