package com.example.ecapi.payment.provider;

import com.example.ecapi.domain.Order;
import com.example.ecapi.payment.CheckoutSession;
import com.example.ecapi.payment.PaymentInstructions;
import com.example.ecapi.payment.PaymentInstructions.Row;
import com.example.ecapi.payment.PaymentProvider;
import com.example.ecapi.service.PaymentDeadlines;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 銀行振込。<strong>外部APIもWebhookも持たない</strong>決済手段が、Stripe とまったく同じ
 * {@link PaymentProvider} 契約に収まることを実証するための第2実装。
 *
 * <p>これが成立するかどうかが、抽象の粒度が正しいかの試金石だった。決済SPIを
 * 「外部APIを叩くもの」として設計してしまうと、EC で実際に最初に増える手段
 * （銀行振込・代金引換）が最初から入らない。
 *
 * <p>入金確認は人手なので、管理画面から注文を PAID にした時点で確定する。その経路でも
 * 在庫コミットと {@code OrderPaidEvent} は Stripe と同一の道を通る
 * （{@code OrderService#updateStatus} → {@code applyPaid}）。
 */
@Component
public class BankTransferPaymentProvider implements PaymentProvider {

    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年M月d日 H時mm分").withZone(ZoneId.of("Asia/Tokyo"));

    private final BankTransferProperties props;
    private final PaymentDeadlines deadlines;
    private final String contextPath;

    public BankTransferPaymentProvider(
            BankTransferProperties props,
            PaymentDeadlines deadlines,
            @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}") String contextPath) {
        this.props = props;
        this.deadlines = deadlines;
        // 本番は lab.4510.be/ec/ 配下に載るので、ストアのトップの URL は context-path を含める。
        this.contextPath = contextPath;
    }

    @Override
    public String id() {
        return PaymentDeadlines.BANK_TRANSFER;
    }

    @Override
    public String displayName() {
        return "銀行振込（前払い）";
    }

    @Override
    public boolean isEnabled() {
        return props.isEnabled();
    }

    @Override
    public CheckoutSession createSession(Order order) {
        // 振込人名義に添えてもらう照合番号。注文IDから決まるので、入金明細と突き合わせできる。
        // 案内はストアの画面がその場に出す（応答の instructions）。redirectUrl はストアのトップ
        // （案内を知らない旧い画面が移動しても、トップに戻るだけ）。
        return new CheckoutSession(reference(order), contextPath + "/");
    }

    @Override
    public Optional<PaymentInstructions> instructions(Order order) {
        String reference = reference(order);
        List<Row> rows = List.of(
                new Row("注文番号", String.valueOf(order.getId())),
                new Row("お振込金額（税込）", order.getTotalAmount().stripTrailingZeros().toPlainString() + " 円"),
                new Row("お振込先", props.getAccountInfo() == null ? "" : props.getAccountInfo()),
                new Row("照合番号", reference),
                new Row("お支払い期限", DEADLINE_FORMAT.format(deadlines.bankTransferDeadline(order))));
        List<String> notes = new ArrayList<>();
        notes.add("入金確認後（最短" + props.getNoteDays() + "営業日）に発送手配へ進みます。");
        notes.add("お振込人名義の先頭に照合番号 " + reference + " をご記入ください。");
        if (props.getNotice() != null && !props.getNotice().isBlank()) {
            notes.add(props.getNotice());
        }
        return Optional.of(new PaymentInstructions("お振込のご案内", rows, notes));
    }

    private static String reference(Order order) {
        return "BT-%06d".formatted(order.getId());
    }
}
