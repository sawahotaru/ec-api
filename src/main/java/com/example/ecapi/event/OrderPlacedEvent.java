package com.example.ecapi.event;

import com.example.ecapi.domain.Order;
import java.math.BigDecimal;

/**
 * 注文が成立し、在庫を引き当てた（PENDING）。<strong>まだ支払われていない。</strong>
 *
 * <p>ゲストにとってはここが唯一「照会トークンを手渡せる」機会になる。トークンは注文確定
 * バナーに一度出るだけで、閉じれば失われる——そして支払いができるのは PENDING の間だけ
 * なので、戻る手段が無いと注文がそのまま失効する。確認メールに照会リンクを載せるのは
 * その穴を塞ぐため。
 *
 * <p>会員注文でも発行する（{@link #guestToken()} は null）。「注文を受け付けました」の
 * 通知は決済の有無によらず要る業務だから。
 */
public record OrderPlacedEvent(
        Long orderId,
        String contactEmail,
        BigDecimal totalAmount,
        String guestToken) implements OrderEvent {

    public static OrderPlacedEvent of(Order order) {
        return new OrderPlacedEvent(order.getId(), order.getContactEmail(),
                order.getTotalAmount(), order.getOrderToken());
    }

    @Override
    public String type() {
        return "placed";
    }
}
