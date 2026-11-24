package com.example.ecapi.service;

import com.example.ecapi.domain.CartItem;
import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.User;
import com.example.ecapi.event.OrderCancelledEvent;
import com.example.ecapi.event.OrderExpiredEvent;
import com.example.ecapi.event.OrderPaidEvent;
import com.example.ecapi.event.OrderPlacedEvent;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.payment.ExpireResult;
import com.example.ecapi.payment.MinorUnits;
import com.example.ecapi.payment.PaymentCallback;
import com.example.ecapi.payment.PaymentProvider;
import com.example.ecapi.payment.PaymentProviderRegistry;
import com.example.ecapi.pricing.OrderPricer;
import com.example.ecapi.pricing.OrderPricing;
import com.example.ecapi.pricing.PricedLine;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final TaxService taxService;
    private final CouponService couponService;
    private final OrderPricer orderPricer;
    private final CheckoutLimits limits;
    private final ApplicationEventPublisher events;
    private final PaymentProviderRegistry registry;

    public OrderService(OrderRepository orderRepository,
                        CartItemRepository cartItemRepository,
                        ProductRepository productRepository,
                        TaxService taxService,
                        CouponService couponService,
                        OrderPricer orderPricer,
                        CheckoutLimits limits,
                        ApplicationEventPublisher events,
                        PaymentProviderRegistry registry) {
        this.limits = limits;
        this.registry = registry;
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.taxService = taxService;
        this.couponService = couponService;
        this.orderPricer = orderPricer;
        this.events = events;
    }

    /**
     * Turns the logged-in user's cart into a PENDING order: snapshots price/name and
     * <em>reserves</em> (holds) stock — all in one transaction. The hold becomes a real
     * stock decrement only when payment is confirmed (see {@link PaymentService}); it is
     * released if the order is cancelled or expires unpaid.
     */
    /** クーポン無しのチェックアウト。 */
    @Transactional
    public Order checkout(User user) {
        return checkout(user, null);
    }

    @Transactional
    public Order checkout(User user, String couponCode) {
        List<CartItem> cartItems = cartItemRepository.findByUserId(user.getId());
        if (cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // Reserve in a consistent order (by product id) to avoid deadlocks when
        // different carts share items.
        Map<Long, Integer> quantities = new TreeMap<>();
        for (CartItem ci : cartItems) {
            quantities.merge(ci.getProduct().getId(), ci.getQuantity(), Integer::sum);
        }

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        buildAndReserve(order, quantities, couponCode);

        Order saved = orderRepository.save(order);
        cartItemRepository.deleteByUserId(user.getId());
        events.publishEvent(OrderPlacedEvent.of(saved));
        return saved;
    }

    /**
     * Guest checkout: no account and no server-side cart. The caller passes the line
     * items directly. Stock is reserved exactly as for a logged-in checkout, and an
     * unguessable {@code orderToken} is issued so the guest can later view and pay the
     * order without logging in.
     *
     * @param email      guest contact email (validated at the DTO layer)
     * @param quantities productId → quantity (already de-duplicated / merged)
     */
    /** クーポン無しのゲストチェックアウト。 */
    @Transactional
    public Order guestCheckout(String email, Map<Long, Integer> quantities) {
        return guestCheckout(email, quantities, null);
    }

    @Transactional
    public Order guestCheckout(String email, Map<Long, Integer> quantities, String couponCode) {
        if (quantities == null || quantities.isEmpty()) {
            throw new BadRequestException("No items to order");
        }
        // TreeMap keeps a stable product-id order (deadlock avoidance) even if the
        // caller passed an unordered map.
        Map<Long, Integer> ordered = new TreeMap<>(quantities);

        Order order = new Order();
        order.setGuestEmail(email);
        order.setOrderToken(UUID.randomUUID().toString());
        order.setStatus(OrderStatus.PENDING);
        buildAndReserve(order, ordered, couponCode);

        Order saved = orderRepository.save(order);
        // ゲストはこの通知でしか照会トークンを持ち帰れない（画面のバナーは閉じれば消える）。
        events.publishEvent(OrderPlacedEvent.of(saved));
        return saved;
    }

    /**
     * Reserves stock, prices the order, and snapshots the result onto it.
     *
     * <p>Reservation and pricing are now two separate passes. They used to be one loop,
     * which is why the shop could not quote a price without holding inventory, and why
     * every new money concept (discount, shipping) would have had to be threaded through
     * the stock code. The stock hold still happens first — if the cart cannot be filled,
     * no price is worth computing.
     *
     * <p>Rates and prices are frozen here: a later rate change or price edit never
     * rewrites this order.
     */
    private void buildAndReserve(Order order, Map<Long, Integer> quantities, String couponCode) {
        LocalDate today = LocalDate.now();
        order.setPricingMode(taxService.pricingMode());

        // --- 1注文として受け付けてよい形か（在庫を触る前に落とす） ---
        if (limits.maxLinesPerOrder() > 0 && quantities.size() > limits.maxLinesPerOrder()) {
            throw new BadRequestException(
                    "1回のご注文でお求めいただける商品は" + limits.maxLinesPerOrder() + "種類までです");
        }

        // --- 在庫の引当（不足ならここで止まる。値段の計算より先） ---
        Map<Product, Integer> lines = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();
            if (quantity < 1) {
                throw new BadRequestException("Quantity must be at least 1 for product " + productId);
            }
            if (limits.maxQuantityPerItem() > 0 && quantity > limits.maxQuantityPerItem()) {
                throw new BadRequestException(
                        "同じ商品は1回のご注文で" + limits.maxQuantityPerItem() + "点までです");
            }
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new NotFoundException("Product no longer exists: " + productId));

            // Atomic conditional hold: only succeeds if enough is still sellable *and*
            // unpaid holds stay under the configured share of this product's stock.
            // DB-level guard prevents overselling under concurrent checkouts.
            int updated = productRepository.reserveStock(productId, quantity, limits.maxHoldPercent());
            if (updated == 0) {
                // 同じ 0 行でも理由は2つある。素の在庫が足りないなら従来どおりの案内で正しいが、
                // 「在庫はあるのに引当上限で断った」ときに「在庫がありません」と言うのは嘘で、
                // しかも時間で解ける話なのに客は諦めてしまう。
                if (product.getAvailable() < quantity) {
                    throw new BadRequestException("Not enough stock for '" + product.getName()
                            + "'. Available: " + product.getAvailable());
                }
                throw new ConflictException("'" + product.getName()
                        + "' はただいま仮押さえが集中しています。しばらく時間をおいてお試しください。");
            }
            lines.put(product, quantity);
        }

        // --- クーポン: 商品合計が確定してからでないと最低金額の判定ができない ---
        BigDecimal itemsList = lines.entrySet().stream()
                .map(e -> e.getKey().getPrice().multiply(BigDecimal.valueOf(e.getValue())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Coupon coupon = couponService.validate(couponCode, itemsList, today);

        // --- 金額（商品 → 割引 → 送料 → 税 → 合計） ---
        OrderPricing pricing = orderPricer.price(lines, coupon, today);
        for (PricedLine line : pricing.lines()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(line.product());
            orderItem.setProductName(line.product().getName());
            orderItem.setUnitPrice(line.unitPrice());
            orderItem.setQuantity(line.quantity());
            orderItem.setTaxCategory(line.taxCategory());
            orderItem.setTaxRatePercent(line.taxRate());
            orderItem.setTaxAmount(line.tax());
            orderItem.setDiscountAmount(line.discount());
            order.addItem(orderItem);
        }

        order.setSubtotalAmount(pricing.itemSubtotal());
        order.setDiscountAmount(pricing.discount());
        order.setShippingAmount(pricing.shipping());
        order.setTaxAmount(pricing.tax());
        order.setTotalAmount(pricing.total());
        order.setCouponCode(pricing.couponCode());

        // 引き換え数を進めるのは最後。ここまでで例外が出た注文はそもそも成立していない。
        couponService.redeem(coupon);
    }

    /**
     * 決済手段を区別せず、{@code cutoff} より前に作られた PENDING を全部期限切れにする
     * （{@link #expireStalePendingOrders(java.time.Instant, java.time.Instant)} の両方に同じ時刻を渡す）。
     */
    @Transactional
    public int expireStalePendingOrders(java.time.Instant cutoff) {
        return expireStalePendingOrders(cutoff, cutoff);
    }

    /**
     * Expires stale PENDING orders: releases each order's held stock and marks it EXPIRED.
     * 銀行振込を選んだ注文は {@code bankCutoff}、それ以外は {@code cardCutoff} より前に作られたものだけ。
     * Runs in one transaction; release is DB-guarded so it stays correct even if two sweeps overlap.
     * 引当の解放は商品 id の順に、同じ商品の数量を足してから 1 回で行う（振込の取り置きの数え方 商品ごとの上限 が
     * 商品の行を id の順にロックするので、逆の順で触ってデッドロックしないようにする）。
     * Returns the number of orders expired.
     */
    @Transactional
    public int expireStalePendingOrders(java.time.Instant cardCutoff, java.time.Instant bankCutoff) {
        List<Order> stale = orderRepository.findStalePending(cardCutoff, bankCutoff);
        List<Order> toClose = new java.util.ArrayList<>();
        Map<Long, Integer> release = new TreeMap<>();
        int expired = 0;
        for (Order order : stale) {
            // ロック待ちの間に、お客さまの取り消し・入金の確定・管理画面の変更が済んでいることがある。
            // PENDING でなくなっていたら触らない（引当を 2 回戻さない・状態を上書きしない）。
            if (order.getStatus() != OrderStatus.PENDING) {
                continue;
            }
            expired++;
            for (OrderItem item : order.getItems()) {
                release.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
            }
            couponService.release(order.getCouponCode());
            order.setStatus(OrderStatus.EXPIRED);
            events.publishEvent(OrderExpiredEvent.of(order));
            if (order.getPaymentProvider() != null) {
                toClose.add(paymentProbe(order));
            }
        }
        release.forEach(productRepository::releaseStock);
        // 決済側の開いている支払いの失効は、コミットの後に 1 件ずつ（多数の注文の行を持ったまま外部を呼ばない）。
        if (!toClose.isEmpty()) {
            afterCommit(() -> toClose.forEach(this::expireAfterClose));
        }
        return expired;
    }

    /**
     * 一度も支払われないまま終わった古い注文を、行ごと削除する。
     *
     * <p>{@code ContactRetentionScheduler} は連絡先だけを伏せて<strong>行は残す</strong>。
     * それは「消すと売上集計から過去が消える」ためで、PAID については今も正しい。
     * だが EXPIRED / CANCELLED は集計に1円も入らないので、残す理由が無い——そして
     * 注文は<strong>未認証で何件でも作れる</strong>ので、残し続けること自体が
     * 小さなVMに対するディスク枯渇の経路になる（{@code guestEmail} は攻撃者が決める文字列）。
     *
     * <p>明細も一緒に消える（{@code Order} → {@code OrderItem} は cascade + orphanRemoval）。
     * 引当はこの時点で既に解放済み（EXPIRED / CANCELLED になった時点で戻している）。
     *
     * @param batchSize 1回の掃除で消す上限。全部消すまで回さないのは、溜まった環境で
     *                  1トランザクションが巨大にならないようにするため（次の周回で続く）。
     * @return 消した件数
     */
    @Transactional
    public int purgeAbandonedOrders(java.time.Instant cutoff, int batchSize) {
        List<OrderStatus> abandoned = List.of(OrderStatus.EXPIRED, OrderStatus.CANCELLED);
        Page<Order> doomed = orderRepository.findByStatusInAndCreatedAtBefore(
                abandoned, cutoff, org.springframework.data.domain.PageRequest.of(0, batchSize));
        if (doomed.isEmpty()) {
            return 0;
        }
        orderRepository.deleteAll(doomed.getContent());
        return doomed.getNumberOfElements();
    }

    @Transactional(readOnly = true)
    public Page<Order> findMyOrders(User user, Pageable pageable) {
        return orderRepository.findByUserId(user.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public Order getMyOrder(User user, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    /** Guest order lookup — the token authenticates the request in place of a login. */
    @Transactional(readOnly = true)
    public Order getGuestOrder(Long orderId, String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Order token is required");
        }
        return orderRepository.findByIdAndOrderToken(orderId, token)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Order get(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    /**
     * 管理画面からのステータス変更。単なる代入ではなく、在庫とイベントの整合を取る唯一の
     * 入口にしてある。
     *
     * <p>とくに PAID への変更は {@link #applyPaid} を通す。以前はここが素の代入だったため、
     * 管理者が手で PAID にすると<strong>引当が実在庫の減算に変換されないまま</strong>になり、
     * 在庫が過大に残っていた（Stripe の Webhook 経由だけが正しく処理していた）。銀行振込の
     * ように人手で入金確認する手段が入ると常用経路になるため、ここで塞いでいる。
     */
    @Transactional
    public Order updateStatus(Long orderId, OrderStatus status) {
        // トランザクションの最初の読み込みを行ロック付きにする（Webhook・掃除と順番に処理される）
        Order order = orderRepository.findWithLockById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        OrderStatus previous = order.getStatus();
        if (previous == status) {
            return order;
        }
        switch (status) {
            case PAID -> {
                applyPaid(order, null, null);
                if (order.getStatus() == OrderStatus.PAID && previous != OrderStatus.PAID) {
                    // ほかの手段（振込の入金確認）で払われた。開いたままの決済ページを、コミットの後に失効させる
                    Order probe = paymentProbe(order);
                    afterCommit(() -> expireAfterPaid(probe));
                }
            }
            case CANCELLED -> {
                // 未払い注文のキャンセルは引当を戻す。PAID 済みは既に実在庫が減っているので
                // ここで戻すと在庫を二重に増やしてしまう。クーポンの引き換え数も同じ理屈で、
                // 売上にならなかった注文の分だけ戻す（期限切れ済みは既に戻っている）。
                if (previous == OrderStatus.PENDING) {
                    cancelPending(order);
                } else {
                    order.setStatus(OrderStatus.CANCELLED);
                    events.publishEvent(OrderCancelledEvent.of(order, previous.name()));
                }
            }
            default -> order.setStatus(status);
        }
        return orderRepository.save(order);
    }

    /**
     * お支払い待ちの注文の取り消し（管理画面とお客さまで同じ部品）。決済側の開いている支払いを先に閉じ
     * （閉じられない・もう払われているときは取り消さない）、引当とクーポンを戻して CANCELLED にする。
     * 呼び出し側が行ロックを取っていること。
     */
    private void cancelPending(Order order) {
        requireOpenPaymentClosed(order);
        releaseHeldItems(order);
        couponService.release(order.getCouponCode());
        order.setStatus(OrderStatus.CANCELLED);
        events.publishEvent(OrderCancelledEvent.of(order, OrderStatus.PENDING.name()));
    }

    /**
     * 会員が自分のお支払い待ちの注文を取り消す。本人確認と行ロックを 1 本のクエリで行い
     * （トランザクションの最初の読み込み）、他人の注文・無い注文は 404、PENDING 以外は 409。
     */
    @Transactional
    public Order cancelByMember(User user, Long orderId) {
        Order order = orderRepository.findLockedByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        return cancelByCustomer(order);
    }

    /** ゲストが注文トークンで、自分のお支払い待ちの注文を取り消す。トークンが空なら 400、違えば 404。 */
    @Transactional
    public Order cancelByGuest(Long orderId, String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Order token is required");
        }
        Order order = orderRepository.findLockedByIdAndOrderToken(orderId, token)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        return cancelByCustomer(order);
    }

    private Order cancelByCustomer(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ConflictException("このご注文は取り消せません");
        }
        cancelPending(order);
        Order saved = orderRepository.save(order);
        log.info("Order {} cancelled by customer", saved.getId());
        return saved;
    }

    /**
     * 決済側の通知を注文と照合してから確定する（Webhook の入口）。
     * 未入金・通貨違い・金額違いは PAID にせず、warn を残して終わる（応答は 200）。
     * 照合は注文の状態に関係なく先に行う。金額は補助単位の整数で比べる。
     * 注文は行ロック付きで読む（取り消し・掃除・管理画面の変更と順番に処理される）。
     */
    @Transactional
    public void confirmPayment(PaymentCallback callback, String providerId, String storeCurrency) {
        Order order = orderRepository.findWithLockById(callback.orderId()).orElse(null);
        if (order == null) {
            log.warn("Payment callback referenced unknown order {}", callback.orderId());
            return;
        }
        Long orderId = order.getId();
        String session = callback.reference();
        if (!callback.paid()) {
            log.warn("PAYMENT_NOT_PAID order={} session={}", orderId, session);
            return;
        }
        long expected = MinorUnits.of(order.getTotalAmount(), storeCurrency);
        if (callback.currency() == null
                || !callback.currency().equalsIgnoreCase(storeCurrency)
                || callback.amountMinor() == null
                || expected != callback.amountMinor()) {
            log.warn("PAYMENT_MISMATCH order={} session={} expected={} {} actual={} {}",
                    orderId, session, expected, storeCurrency,
                    callback.amountMinor(), callback.currency());
            return;
        }
        applyPaid(order, providerId, session);
        orderRepository.save(order);
    }

    /**
     * 決済確定。照合済み、または人が確認した確定として扱う（金額の照合はしない）。
     * Stripe の Webhook は {@link #confirmPayment} を通る。こちらは管理画面・銀行振込・テスト用。
     *
     * @param providerId 決済手段の id。手動確定なら null（既存の値を保つ）
     * @param reference  決済側の参照ID。無ければ null
     */
    @Transactional
    public void markPaid(Long orderId, String providerId, String reference) {
        orderRepository.findById(orderId).ifPresentOrElse(
                order -> {
                    applyPaid(order, providerId, reference);
                    orderRepository.save(order);
                },
                () -> log.warn("Payment callback referenced unknown order {}", orderId));
    }

    /**
     * 引当（hold）を実在庫の減算へ変換し、PAID にして {@link OrderPaidEvent} を発行する。
     * PENDING ガードにより、Webhook が重複配信されても冪等。
     */
    private void applyPaid(Order order, String providerId, String reference) {
        Long orderId = order.getId();
        // 決済手段・参照を書き換えてよいのは、これから PAID にする注文（PENDING・EXPIRED）だけ。
        // 支払い済み・キャンセル済みの注文では、受信値で上書きしない。
        switch (order.getStatus()) {
            case PENDING -> {
                recordPayment(order, providerId, reference);
                for (OrderItem item : order.getItems()) {
                    productRepository.commitStock(item.getProduct().getId(), item.getQuantity());
                }
                order.setStatus(OrderStatus.PAID);
                log.info("Order {} marked PAID via {} (stock committed)",
                        orderId, providerId != null ? providerId : "manual update");
                events.publishEvent(OrderPaidEvent.of(order));
            }
            case EXPIRED -> {
                // 保留期限切れで引当を解放した後に入金が届いた。支払いは受けるが在庫はもう
                // 触らない（再コミットすると売り越す）。手動での棚卸し調整が要る。
                recordPayment(order, providerId, reference);
                order.setStatus(OrderStatus.PAID);
                log.warn("Order {} was EXPIRED when payment arrived — marked PAID WITHOUT "
                        + "stock decrement. Manual stock reconciliation may be needed.", orderId);
                events.publishEvent(OrderPaidEvent.of(order));
            }
            case PAID, SHIPPED, DELIVERED -> {
                // 参照が無い（管理画面の手動確定）か、記録と同じ（再送）なら重複として無視する。
                // 別のセッションの入金は二重払いの可能性があるので、上書きせず要返金確認を残す。
                if (reference != null && !reference.equals(order.getPaymentReference())) {
                    log.warn("PAYMENT_DUPLICATE_SESSION order={} session={}", orderId, reference);
                } else {
                    log.info("Order {} already {} — ignoring duplicate payment confirmation",
                            orderId, order.getStatus());
                }
            }
            case CANCELLED -> {
                // キャンセル済みの注文に入金が届いた（要返金確認）。参照は上書きしない。
                if (reference != null) {
                    log.warn("PAYMENT_AFTER_CLOSE order={} status={} session={}",
                            orderId, order.getStatus(), reference);
                } else {
                    log.info("Order {} already {} — ignoring duplicate payment confirmation",
                            orderId, order.getStatus());
                }
            }
            default -> log.warn("Order {} has unexpected status {} when payment arrived — ignored",
                    orderId, order.getStatus());
        }
    }

    private void recordPayment(Order order, String providerId, String reference) {
        if (providerId != null) {
            order.setPaymentProvider(providerId);
        }
        if (reference != null) {
            order.setPaymentReference(reference);
        }
    }

    /** 取り消しの前に決済側の開いている支払いを閉じる。閉じられない・払われているなら取り消さない（409）。 */
    private void requireOpenPaymentClosed(Order order) {
        PaymentProvider provider = registry.find(order.getPaymentProvider()).orElse(null);
        if (provider == null) {
            return;
        }
        ExpireResult result = provider.expireOpenPayment(order);
        if (result == ExpireResult.ALREADY_COMPLETED) {
            throw new ConflictException("お支払いが完了しています。確認が済むまでお待ちください");
        }
        if (result == ExpireResult.FAILED) {
            throw new ConflictException("取り消しできませんでした。時間をおいてお試しください");
        }
    }

    /** コミットの後に行う。トランザクションの外から呼ばれたときはその場で行う。 */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 失効の呼び出しに渡す、注文の ID・決済手段・参照だけを持つ控え（永続化しない）。 */
    private static Order paymentProbe(Order order) {
        Order probe = new Order();
        probe.setId(order.getId());
        probe.setPaymentProvider(order.getPaymentProvider());
        probe.setPaymentReference(order.getPaymentReference());
        return probe;
    }

    /** 期限切れにした注文の決済ページを失効させる。失敗しても期限切れのまま（警告だけ残す）。 */
    private void expireAfterClose(Order probe) {
        try {
            PaymentProvider provider = registry.find(probe.getPaymentProvider()).orElse(null);
            if (provider == null) {
                return;
            }
            if (provider.expireOpenPayment(probe) == ExpireResult.FAILED) {
                log.warn("PAYMENT_EXPIRE_FAILED order={} session={}", probe.getId(), probe.getPaymentReference());
            }
        } catch (RuntimeException e) {
            log.warn("PAYMENT_EXPIRE_FAILED order={} session={} type={}", probe.getId(),
                    probe.getPaymentReference(), e.getClass().getSimpleName());
        }
    }

    /** ほかの手段で PAID になった注文の決済ページを失効させる。PAID は戻さない。 */
    private void expireAfterPaid(Order probe) {
        try {
            PaymentProvider provider = registry.find(probe.getPaymentProvider()).orElse(null);
            if (provider == null) {
                return;
            }
            ExpireResult result = provider.expireOpenPayment(probe);
            if (result == ExpireResult.ALREADY_COMPLETED) {
                log.warn("PAYMENT_PAID_TWICE order={} session={}", probe.getId(), probe.getPaymentReference());
            } else if (result == ExpireResult.FAILED) {
                log.warn("PAYMENT_EXPIRE_FAILED order={} session={}", probe.getId(), probe.getPaymentReference());
            }
        } catch (RuntimeException e) {
            log.warn("PAYMENT_EXPIRE_FAILED order={} session={} type={}", probe.getId(),
                    probe.getPaymentReference(), e.getClass().getSimpleName());
        }
    }

    private void releaseHeldItems(Order order) {
        for (OrderItem item : order.getItems()) {
            productRepository.releaseStock(item.getProduct().getId(), item.getQuantity());
        }
    }
}
