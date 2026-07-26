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

    private final OrderService orderService;
    private final long holdMinutes;

    public OrderExpiryScheduler(OrderService orderService,
                                @Value("${app.order.hold-minutes:30}") long holdMinutes) {
        this.orderService = orderService;
        this.holdMinutes = holdMinutes;
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
}
