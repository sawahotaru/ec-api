package com.example.ecapi.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases stock held by orders that were never paid. A checkout reserves stock; if the
 * buyer walks away, that hold would otherwise sit on the inventory forever. This sweep
 * expires any order still PENDING after the configured hold window and returns its stock
 * to the sellable pool.
 */
@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    /** 1回の掃除で消す上限。溜まった環境で1トランザクションが巨大にならないようにする。 */
    private static final int PURGE_BATCH = 500;

    private final OrderService orderService;
    private final long holdMinutes;
    private final int purgeAfterDays;

    public OrderExpiryScheduler(OrderService orderService,
                                @Value("${app.order.hold-minutes:30}") long holdMinutes,
                                @Value("${app.order.purge-abandoned-days:30}") int purgeAfterDays) {
        this.orderService = orderService;
        this.holdMinutes = holdMinutes;
        this.purgeAfterDays = purgeAfterDays;
    }

    /** Runs every minute; expires PENDING orders older than the hold window. */
    @Scheduled(fixedDelayString = "${app.order.expiry-sweep-ms:60000}",
            initialDelayString = "${app.order.expiry-sweep-ms:60000}")
    public void expireStaleOrders() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(holdMinutes));
        int expired = orderService.expireStalePendingOrders(cutoff);
        if (expired > 0) {
            log.info("Expired {} unpaid order(s) older than {} min; released their held stock",
                    expired, holdMinutes);
        }
    }

    /**
     * 支払われずに終わった古い注文を行ごと消す。
     *
     * <p>注文は<strong>未認証で何件でも作れる</strong>ので、残し続けること自体が
     * ディスク枯渇の経路になる。消してよいのは EXPIRED / CANCELLED だけで、
     * 売上集計は PAID しか数えないため集計は1円も動かない。
     *
     * <p>{@code app.order.purge-abandoned-days=0} で無効。期限切れの掃除とは別メソッドに
     * してあるのは、片方を止めたときにもう片方まで止まらないようにするため。
     */
    @Scheduled(fixedDelayString = "${app.order.purge-sweep-ms:3600000}",
            initialDelayString = "${app.order.purge-sweep-ms:3600000}")
    public void purgeAbandonedOrders() {
        if (purgeAfterDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(purgeAfterDays));
        int purged = orderService.purgeAbandonedOrders(cutoff, PURGE_BATCH);
        if (purged > 0) {
            log.info("Purged {} unpaid order(s) older than {} days (expired/cancelled only; "
                    + "paid orders are never deleted)", purged, purgeAfterDays);
        }
    }
}
