package com.example.ecapi.event.listener;

import com.example.ecapi.event.OrderCancelledEvent;
import com.example.ecapi.event.OrderEvent;
import com.example.ecapi.event.OrderEventListener;
import com.example.ecapi.event.OrderExpiredEvent;
import com.example.ecapi.event.OrderPaidEvent;
import com.example.ecapi.event.OrderPlacedEvent;
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
    private final String baseUrl;
    private final int holdMinutes;
    private boolean warnedMissingSender;
    private boolean warnedMissingBaseUrl;

    public MailOrderEventListener(ObjectProvider<JavaMailSender> mailSender,
                                  @Value("${app.notify.mail.from:no-reply@example.com}") String from,
                                  @Value("${app.notify.mail.admin:}") String adminEmail,
                                  @Value("${app.public-base-url:}") String baseUrl,
                                  @Value("${app.order.hold-minutes:30}") int holdMinutes) {
        this.mailSender = mailSender;
        this.from = from;
        this.adminEmail = adminEmail;
        // 末尾スラッシュの有無で "//#/" のようなリンクにならないようにここで正規化する
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.holdMinutes = holdMinutes;
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
        //
        // withLookupLink は「この通知にゲスト照会リンクを添えるか」。まだ本人が注文に
        // 用があるとき（支払い前・発送待ち）だけ true にする。トークンは資格情報なので、
        // 用の無い通知（キャンセル済み・失効済み）にまで載せない。
        record Mail(String subject, String body, boolean withLookupLink) {
        }
        Mail mail = switch (event) {
            case OrderPlacedEvent placed -> new Mail(
                    "ご注文を受け付けました（注文番号 " + placed.orderId() + "）",
                    """
                    ご注文を受け付けました。まだお支払いは完了していません。

                    注文番号: %d
                    お支払金額: %s 円（税込）

                    %d分以内にお支払いが確認できない場合、ご注文は自動的にキャンセルとなり、
                    お取り置きしている商品は販売可能な状態に戻ります。
                    """.formatted(placed.orderId(),
                            placed.totalAmount().stripTrailingZeros().toPlainString(), holdMinutes),
                    true);
            case OrderPaidEvent paid -> new Mail(
                    "ご注文ありがとうございます（注文番号 " + paid.orderId() + "）",
                    """
                    ご注文を承りました。

                    注文番号: %d
                    お支払金額: %s 円（税込）

                    発送準備が整い次第あらためてご連絡いたします。
                    """.formatted(paid.orderId(), paid.totalAmount().stripTrailingZeros().toPlainString()),
                    true);
            case OrderCancelledEvent cancelled -> new Mail(
                    "ご注文をキャンセルしました（注文番号 " + cancelled.orderId() + "）",
                    "注文番号 %d のご注文をキャンセルいたしました。".formatted(cancelled.orderId()),
                    false);
            case OrderExpiredEvent expired -> new Mail(
                    "ご注文の有効期限が切れました（注文番号 " + expired.orderId() + "）",
                    """
                    注文番号 %d は、お支払いが確認できなかったため自動的にキャンセルされました。
                    お手数ですが、あらためてご注文ください。
                    """.formatted(expired.orderId()),
                    false);
        };

        String body = mail.withLookupLink() ? mail.body() + lookupSection(event) : mail.body();
        send(sender, event.contactEmail(), mail.subject(), body);
        if (event instanceof OrderPaidEvent && !adminEmail.isBlank()) {
            // 管理者控えには照会トークンを載せない（管理画面から見られるので不要な資格情報）。
            send(sender, adminEmail, "[受注] " + mail.subject(), mail.body());
        }
    }

    /**
     * ゲスト注文に「あとで注文へ戻る手段」を添える。会員注文では空文字（ログインすれば
     * 注文履歴から辿れるため、メールに資格情報を載せる必要が無い）。
     *
     * <p>{@code app.public-base-url} が未設定なら直リンクは作れないので、代わりに注文番号と
     * トークンを本文に置く（照会ページに手入力すれば同じ結果になる）。壊れた localhost の
     * URL を送りつけるより、入力してもらう方が実害が無い。
     */
    private String lookupSection(OrderEvent event) {
        String token = event.guestToken();
        if (token == null || token.isBlank()) {
            return "";
        }
        if (baseUrl.isEmpty()) {
            if (!warnedMissingBaseUrl) {
                warnedMissingBaseUrl = true;
                log.warn("app.public-base-url is not set — guest order mails carry the lookup token "
                        + "but no direct link. Set PUBLIC_BASE_URL (e.g. https://example.com/ec).");
            }
            return """

                    ▼ ご注文の確認・お支払い
                    ストアの「注文を確認」ページで、下記を入力してください。
                      注文番号: %d
                      照会トークン: %s
                    """.formatted(event.orderId(), token);
        }
        return """

                ▼ ご注文の確認・お支払いはこちら
                %s/#/orders/guest/%d/%s

                （このURLはご注文を開くための鍵です。他の方に転送しないでください）
                """.formatted(baseUrl, event.orderId(), token);
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
