package com.example.ecapi.event;

import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * コアが publish した {@link OrderEvent} を、登録済みの {@link OrderEventListener}
 * プラグインへ配る。
 *
 * <p>Spring の {@code @TransactionalEventListener} を直に各プラグインへ書かせず1枚挟んで
 * いるのは <strong>障害の隔離</strong>のため。メール送信プラグインがSMTP障害で落ちても、
 * Slack通知プラグインと会計連携プラグインは走りきる。
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} 固定なのも意図的で、注文がロールバックしたのに
 * 「ご注文ありがとうございます」メールだけ飛ぶ事故を構造的に防ぐ。
 */
@Component
public class OrderEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventDispatcher.class);

    private final List<OrderEventListener> listeners;

    /**
     * {@code ObjectProvider} で受けているのは、リスナーが1つも無い構成でも起動できるように
     * するため（{@code List<T>} の直接注入は空だと Spring が起動失敗させる）。
     */
    public OrderEventDispatcher(ObjectProvider<OrderEventListener> provider) {
        this.listeners = provider.stream()
                .sorted(Comparator.comparingInt(OrderEventListener::priority))
                .toList();
        log.info("Order event listeners registered: {}",
                listeners.isEmpty() ? "(none)" : listeners.stream().map(OrderEventListener::name).toList());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(OrderEvent event) {
        for (OrderEventListener listener : listeners) {
            try {
                listener.onOrderEvent(event);
            } catch (Exception e) {
                // 注文自体は既にコミット済み。ここで投げ直すと後続プラグインが走らないので握る。
                log.error("Order event listener '{}' failed for {} event on order {} — continuing with the rest",
                        listener.name(), event.type(), event.orderId(), e);
            }
        }
    }
}
