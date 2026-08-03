package com.example.ecapi.stats;

import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.stats.StatsDtos.MonthlyPoint;
import com.example.ecapi.stats.StatsDtos.StatusBreakdown;
import com.example.ecapi.stats.StatsDtos.StoreStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理画面の売上統計。
 *
 * <h2>何を「売上」と数えるか</h2>
 * <strong>支払いが済んだ注文だけ</strong>（PAID / SHIPPED / DELIVERED）。作られただけの注文
 * （PENDING）や、失効・キャンセルされた注文は<strong>1円も数えない</strong>。
 * ここを緩めると、在庫を押さえただけの注文が売上に化けて、実際より良い数字が出続ける。
 *
 * <p>一方でそれらを<strong>捨てもしない</strong>。ec-api は注文を消さずに状態を持ち続けるので、
 * 「作られた注文のうち、いくつが売上になったか」を出せる。これは
 * {@code clinic-reservation} 側（キャンセルで行ごと削除）では原理的に出せない数字で、
 * 状態を残す設計が具体的に何を可能にしているかの実例になっている。
 *
 * <h2>時刻の扱い</h2>
 * 月別の切り分けは店舗のタイムゾーン（{@code app.stats.zone}・既定 Asia/Tokyo）で行う。
 * {@code createdAt} は UTC の {@link Instant} なので、UTC のまま月を切ると
 * <strong>日本時間の毎月1日の午前9時までの売上が前月に入る</strong>。
 */
@Service
public class StatsService {

    /** 売上として数える状態。「支払いが確定した」以降だけ。 */
    private static final List<OrderStatus> REVENUE_STATUSES =
            List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    /** 売上にならなかった状態（機会損失として別に数える）。 */
    private static final List<OrderStatus> LOST_STATUSES =
            List.of(OrderStatus.CANCELLED, OrderStatus.EXPIRED);

    private final OrderRepository orderRepository;
    private final ZoneId zone;

    public StatsService(OrderRepository orderRepository,
                        @Value("${app.stats.zone:Asia/Tokyo}") String zone) {
        this.orderRepository = orderRepository;
        this.zone = ZoneId.of(zone);
    }

    @Transactional(readOnly = true)
    public StoreStats collect(int months) {
        List<StatusBreakdown> statuses = orderRepository.statusBreakdown();

        long paidOrders = 0;
        long pendingOrders = 0;
        long lostOrders = 0;
        long allOrders = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        for (StatusBreakdown row : statuses) {
            OrderStatus status = OrderStatus.valueOf(row.status());
            allOrders += row.orders();
            if (REVENUE_STATUSES.contains(status)) {
                paidOrders += row.orders();
                revenue = revenue.add(row.amount());
            } else if (LOST_STATUSES.contains(status)) {
                lostOrders += row.orders();
            } else {
                pendingOrders += row.orders();
            }
        }

        BigDecimal averageOrder = paidOrders == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(paidOrders), 0, RoundingMode.HALF_UP);

        // 転換率の母数は「作られた注文すべて」。まだ支払える PENDING を除くと、
        // 期限切れになる前の注文が母数から消えて、時間帯によって率が上下してしまう。
        BigDecimal conversion = allOrders == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(paidOrders * 1000L / allOrders).divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP);

        return new StoreStats(
                revenue,
                paidOrders,
                averageOrder,
                pendingOrders,
                lostOrders,
                conversion,
                orderRepository.totalDiscount(REVENUE_STATUSES),
                orderRepository.totalShipping(REVENUE_STATUSES),
                monthly(months),
                statuses,
                orderRepository.productSales(REVENUE_STATUSES),
                orderRepository.revenueByProvider(REVENUE_STATUSES),
                orderRepository.couponUsage(REVENUE_STATUSES));
    }

    /**
     * 直近 {@code months} ヶ月の売上推移。<strong>注文の無かった月も 0 で埋める</strong>。
     * 空の月を落とすと横並びが詰まって、落ち込みが落ち込みに見えなくなる。
     */
    private List<MonthlyPoint> monthly(int months) {
        YearMonth current = YearMonth.now(zone);
        YearMonth start = current.minusMonths(months - 1L);
        Instant from = start.atDay(1).atStartOfDay(zone).toInstant();

        Map<YearMonth, long[]> counts = new LinkedHashMap<>();
        Map<YearMonth, BigDecimal> sums = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            YearMonth ym = start.plusMonths(i);
            counts.put(ym, new long[]{0});
            sums.put(ym, BigDecimal.ZERO);
        }

        for (Object[] row : orderRepository.revenueTimeline(REVENUE_STATUSES, from)) {
            YearMonth ym = YearMonth.from(((Instant) row[0]).atZone(zone));
            if (!counts.containsKey(ym)) {
                continue;   // 端数（境界の直前直後）は捨てる。表に出す12ヶ月だけを埋める
            }
            counts.get(ym)[0]++;
            sums.put(ym, sums.get(ym).add((BigDecimal) row[1]));
        }

        List<MonthlyPoint> out = new ArrayList<>(months);
        counts.forEach((ym, n) -> out.add(new MonthlyPoint(ym.toString(), n[0], sums.get(ym))));
        return out;
    }
}
