package com.example.ecapi.service;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderItem;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.TooManyAttemptsException;
import com.example.ecapi.payment.provider.BankTransferProperties;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.security.RequestRateLimiter;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 銀行振込の長い取り置き（既定 14 日）が在庫を握り続けないようにする抑え（3 つの抑え）。
 *
 * <p>確かめる順序は <strong>送信元の回数（メモリだけ）→ 会員の件数（会員だけ・件数の SELECT）→ 商品ごとの上限（商品の行のロック）</strong>。
 * 安いもの・ロックの要らないものから確かめ、商品の行のロックを持つ時間を短くする。
 */
@Component
public class BankTransferGuard {

    static final String S1_MESSAGE = "この商品はお振込でのお取り置きが上限に達しています。カードでお支払いください";
    static final String S2_MESSAGE = "お支払い待ちのお振込のご注文があります。先にそちらをお支払いください";
    static final String S3_MESSAGE = "お振込のお申し込みが続いています。しばらくしてからお試しください";
    static final String BUSY_MESSAGE = "ただいま混み合っています。時間をおいてお試しください";

    private static final String S3_BUCKET = "bank-transfer";
    private static final Duration S3_WINDOW = Duration.ofHours(24);

    private final BankTransferProperties props;
    private final RequestRateLimiter limiter;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public BankTransferGuard(BankTransferProperties props, RequestRateLimiter limiter,
                             OrderRepository orderRepository, ProductRepository productRepository) {
        this.props = props;
        this.limiter = limiter;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /** 送信元の回数: 同じ送信元が振込を選ぶ回数。送信元が分からない呼び出し（要求の外）は数えない。 */
    public void checkSource(String clientKey) {
        if (clientKey == null) {
            return;
        }
        long retryAfter = limiter.acquire(S3_BUCKET, clientKey, props.getMaxPerSource(), S3_WINDOW);
        if (retryAfter > 0) {
            throw new TooManyAttemptsException(S3_MESSAGE, retryAfter);
        }
    }

    /** 会員の件数: 会員 1 人あたりの未払いの振込の件数。ゲストには掛けない（他人のメールで止められるため）。 */
    public void checkMember(Order order) {
        if (order.getUser() == null) {
            return;
        }
        long pending = orderRepository.countByUserIdAndStatusAndPaymentProviderAndIdNot(
                order.getUser().getId(), OrderStatus.PENDING, PaymentDeadlines.BANK_TRANSFER, order.getId());
        if (pending >= props.getMaxPendingPerMember()) {
            throw new ConflictException(S2_MESSAGE);
        }
    }

    /**
     * 商品ごとの上限: 商品ごとの、振込で取り置ける量の上限。対象の商品の行を id の順にロックしてから数える
     * （同時に選ばれても超えない）。振込の取り置きの合計が 1 点以下なら割合に関わらず通す。
     * ロックの待ちが打ち切られた（デッドロックなど）ときは、再試行を促す 409 にする。
     */
    public void checkStock(Order order) {
        Map<Long, Integer> wanted = new TreeMap<>();
        for (OrderItem item : order.getItems()) {
            wanted.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
        }
        if (wanted.isEmpty()) {
            return;
        }
        try {
            productRepository.lockByIdInOrderById(wanted.keySet());
        } catch (PessimisticLockingFailureException e) {
            throw new ConflictException(BUSY_MESSAGE);
        }
        for (Map.Entry<Long, Integer> e : wanted.entrySet()) {
            long total = orderRepository.sumBankTransferHeld(e.getKey(), order.getId()) + e.getValue();
            Integer stock = productRepository.findStockById(e.getKey());
            long limit = (long) (stock == null ? 0 : stock) * props.getMaxHoldPercent();
            if (total > 1 && total * 100 > limit) {
                throw new ConflictException(S1_MESSAGE);
            }
        }
    }
}
