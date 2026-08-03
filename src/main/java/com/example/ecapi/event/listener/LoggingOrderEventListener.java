package com.example.ecapi.event.listener;

import com.example.ecapi.event.OrderEvent;
import com.example.ecapi.event.OrderEventListener;
import com.example.ecapi.event.OrderPaidEvent;
import com.example.ecapi.privacy.ContactMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 標準搭載のリスナー。常に有効で、注文イベントを監査ログとして出力する。
 * 拡張点が生きていることの確認にもなるので、他のプラグインより先（priority=10）に走らせる。
 */
@Component
public class LoggingOrderEventListener implements OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingOrderEventListener.class);

    @Override
    public void onOrderEvent(OrderEvent event) {
        if (event instanceof OrderPaidEvent paid) {
            log.info("[order-audit] order={} event={} total={} via={} contact={}",
                    paid.orderId(), paid.type(), paid.totalAmount(),
                    paid.paymentProviderId() != null ? paid.paymentProviderId() : "manual",
                    ContactMask.maskForLog(paid.contactEmail()));
        } else {
            log.info("[order-audit] order={} event={} total={} contact={}",
                    event.orderId(), event.type(), event.totalAmount(), ContactMask.maskForLog(event.contactEmail()));
        }
    }

    @Override
    public int priority() {
        return 10;
    }

}
