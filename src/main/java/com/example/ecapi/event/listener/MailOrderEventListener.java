package com.example.ecapi.event.listener;

import com.example.ecapi.event.OrderCancelledEvent;
import com.example.ecapi.event.OrderEvent;
import com.example.ecapi.event.OrderEventListener;
import com.example.ecapi.event.OrderExpiredEvent;
import com.example.ecapi.event.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 注文イベントをメール通知に変換するプラグイン第1号。
 * {@code app.notify.mail.enabled=true} で有効化する（既定は無効＝デモ環境で誤送信しない）。
 *
 * <p>コア側にメール送信の知識は一切無い。このクラスを削除しても注文・決済は完全に動く。
 * それが {@link OrderEventListener} という拡張点が機能している証拠になる。
 *
 * <p>{@code JavaMailSender} を {@code ObjectProvider} で受けているのは、SMTPが未設定
 * （{@code spring.mail.host} 無し）でも起動を壊さないため。その場合は警告を1度出して
 * 何もしない。フラグだけ立てて設定を忘れた、が原因不明の起動失敗にならないようにしている。
 */
@Component
@ConditionalOnProperty(name = "app.notify.mail.enabled", havingValue = "true")
public class MailOrderEventListener implements OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(MailOrderEventListener.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String adminEmail;
    private boolean warnedMissingSender;

    public MailOrderEventListener(ObjectProvider<JavaMailSender> mailSender,
                                  @Value("${app.notify.mail.from:no-reply@example.com}") String from,
                                  @Value("${app.notify.mail.admin:}") String adminEmail) {
        this.mailSender = mailSender;
        this.from = from;
        this.adminEmail = adminEmail;
    }

    @Override
    public void onOrderEvent(OrderEvent event) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            if (!warnedMissingSender) {
                warnedMissingSender = true;
                log.warn("app.notify.mail.enabled=true but no JavaMailSender is configured "
                        + "(set spring.mail.host) — order notification mail is disabled");
            }
            return;
        }

        // OrderEvent は sealed なので、種類が増えたらこの switch がコンパイルエラーになる
        // ＝通知漏れを実行時ではなくビルド時に気付ける。
        record Mail(String subject, String body) {
        }
        Mail mail = switch (event) {
            case OrderPaidEvent paid -> new Mail(
                    "ご注文ありがとうございます（注文番号 " + paid.orderId() + "）",
                    """
                    ご注文を承りました。

                    注文番号: %d
                    お支払金額: %s 円（税込）

                    発送準備が整い次第あらためてご連絡いたします。
                    """.formatted(paid.orderId(), paid.totalAmount().stripTrailingZeros().toPlainString()));
            case OrderCancelledEvent cancelled -> new Mail(
                    "ご注文をキャンセルしました（注文番号 " + cancelled.orderId() + "）",
                    "注文番号 %d のご注文をキャンセルいたしました。".formatted(cancelled.orderId()));
            case OrderExpiredEvent expired -> new Mail(
                    "ご注文の有効期限が切れました（注文番号 " + expired.orderId() + "）",
                    """
                    注文番号 %d は、お支払いが確認できなかったため自動的にキャンセルされました。
                    お手数ですが、あらためてご注文ください。
                    """.formatted(expired.orderId()));
        };

        send(sender, event.contactEmail(), mail.subject(), mail.body());
        if (event instanceof OrderPaidEvent && !adminEmail.isBlank()) {
            send(sender, adminEmail, "[受注] " + mail.subject(), mail.body());
        }
    }

    @Override
    public int priority() {
        return 50;
    }

    private void send(JavaMailSender sender, String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            log.debug("No recipient for '{}' — skipping", subject);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
