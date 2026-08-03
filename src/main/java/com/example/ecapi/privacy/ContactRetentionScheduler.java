package com.example.ecapi.privacy;

import com.example.ecapi.domain.Order;
import com.example.ecapi.repository.OrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 古い注文から<strong>連絡先だけ</strong>を消す。
 *
 * <h2>なぜ「注文を消す」ではないのか</h2>
 * 目的は「公開デモに他人のメールアドレスを溜め込まないこと」であって、履歴を失うことではない。
 * 行ごと消すと<strong>売上集計から過去が消える</strong>——統計は「支払い済みの注文」を数えるので、
 * 30日で削除する運用は「30日より前の売上が無かったことになる」という別の嘘を生む。
 *
 * <p>そこで {@code guestEmail} と {@code orderToken} だけを落とす。残るのは金額・商品・状態で、
 * これらは個人を指さない。集計は完全に保たれ、消したい情報だけが消える。
 *
 * <p>⚠️ 副作用として、<strong>ゲストはその注文を照会できなくなる</strong>（トークンが消えるため）。
 * 30日以上前の注文に対する操作は残っていないので、実害はないと判断した。
 *
 * <p>会員注文の連絡先は {@code users} 側にあるのでここでは触らない。デモの会員は
 * シードした1件だけで、そのアドレスは README に載っている公開値。
 */
@Component
public class ContactRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContactRetentionScheduler.class);

    private final OrderRepository orderRepository;
    private final DemoProperties demo;

    public ContactRetentionScheduler(OrderRepository orderRepository, DemoProperties demo) {
        this.orderRepository = orderRepository;
        this.demo = demo;
    }

    /**
     * 既定は無効（{@code app.demo.retention-days=0}）。間隔は控えめでよく、
     * 「1日以内に消える」ことに意味は無い（保持日数の粒度が日なので）。
     */
    @Scheduled(fixedDelayString = "${app.demo.retention-sweep-ms:3600000}")
    @Transactional
    public void purgeOldContacts() {
        int days = demo.retentionDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Order> stale = orderRepository.findByGuestEmailIsNotNullAndCreatedAtBefore(cutoff);
        if (stale.isEmpty()) {
            return;
        }
        for (Order order : stale) {
            order.setGuestEmail(null);
            order.setOrderToken(null);
        }
        log.info("Anonymised contact details on {} order(s) older than {} days", stale.size(), days);
    }
}
