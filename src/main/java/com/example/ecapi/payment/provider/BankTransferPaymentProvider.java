package com.example.ecapi.payment.provider;

import com.example.ecapi.domain.Order;
import com.example.ecapi.payment.CheckoutSession;
import com.example.ecapi.payment.PaymentProvider;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
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

    private final BankTransferProperties props;
    private final String contextPath;

    public BankTransferPaymentProvider(
            BankTransferProperties props,
            @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}") String contextPath) {
        this.props = props;
        // 本番は lab.4510.be/ec/ 配下に載るので、案内ページのURLは context-path を含める。
        this.contextPath = contextPath;
    }

    @Override
    public String id() {
        return "bank_transfer";
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
        String reference = "BT-%06d".formatted(order.getId());
        StringBuilder url = new StringBuilder(
                contextPath + "/api/payments/" + id() + "/instructions?orderId=" + order.getId());
        if (order.getOrderToken() != null) {
            // ゲスト注文はログインが無いので、案内ページの閲覧権限をトークンで担保する。
            url.append("&token=").append(URLEncoder.encode(order.getOrderToken(), StandardCharsets.UTF_8));
        }
        return new CheckoutSession(reference, url.toString());
    }

    @Override
    public Optional<String> instructionsHtml(Order order) {
        String reference = "BT-%06d".formatted(order.getId());
        return Optional.of("""
                <!doctype html><meta charset="utf-8">
                <title>お振込のご案内</title>
                <h1>お振込のご案内</h1>
                <p>下記口座へお振込ください。入金確認後（最短%s営業日）に発送手配へ進みます。</p>
                <table border="1" cellpadding="8" style="border-collapse:collapse">
                  <tr><th align="left">注文番号</th><td>%d</td></tr>
                  <tr><th align="left">お振込金額</th><td>%s 円（税込）</td></tr>
                  <tr><th align="left">お振込先</th><td>%s</td></tr>
                  <tr><th align="left">照合番号</th><td><b>%s</b></td></tr>
                </table>
                <p>お振込人名義の先頭に照合番号 <b>%s</b> をご記入いただくと確認がスムーズです。</p>
                """.formatted(
                escape(props.getNoteDays()),
                order.getId(),
                order.getTotalAmount().stripTrailingZeros().toPlainString(),
                escape(props.getAccountInfo()),
                reference,
                reference));
    }

    /** 口座情報は設定由来（管理者が入力する）ので、案内ページに出す前にエスケープする。 */
    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
