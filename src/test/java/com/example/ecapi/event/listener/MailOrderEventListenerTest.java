package com.example.ecapi.event.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.event.OrderCancelledEvent;
import com.example.ecapi.event.OrderExpiredEvent;
import com.example.ecapi.event.OrderPaidEvent;
import com.example.ecapi.event.OrderPlacedEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 注文通知メールの本文の回帰テスト。
 *
 * <p>ここで固定したいのは主に<strong>ゲスト照会トークンの出しどころ</strong>。
 * トークンは提示できれば注文の閲覧と支払いができる資格情報なので、
 * 「本人に届くべき通知には載る」「それ以外には載らない」の両方が壊れると実害が出る:
 * 前者が壊れるとゲストは注文に戻れなくなり（バナーを閉じたら終わり）、
 * 後者が壊れると不要な相手にまで鍵が渡る。
 *
 * <p>Spring コンテキストは起動しない。検証対象が本文の組み立てだけなので、
 * リスナーを直接 new した方が速く、壊れたときの原因も1箇所に絞れる。
 */
class MailOrderEventListenerTest {

    private static final BigDecimal TOTAL = new BigDecimal("6480");
    private static final String TOKEN = "9f1c8d2e-guest-token";

    private final CapturingMailSender sender = new CapturingMailSender();

    // --- ゲスト注文: 照会リンクが載る ------------------------------------------

    @Test
    @DisplayName("ゲストの注文受付メールに、注文へ戻れる照会リンクが載る")
    void guestPlacedMailCarriesTheLookupLink() {
        listener("https://lab.4510.be/ec", "").onOrderEvent(
                new OrderPlacedEvent(5L, "guest@example.com", TOTAL, TOKEN));

        SimpleMailMessage mail = sender.only();
        assertThat(mail.getTo()).containsExactly("guest@example.com");
        assertThat(mail.getSubject()).contains("ご注文を受け付けました", "5");
        assertThat(mail.getText())
                .contains("https://lab.4510.be/ec/#/orders/guest/5/" + TOKEN)
                .contains("30分以内");   // 引当の保留時間は設定値から出す
    }

    @Test
    @DisplayName("base-url の末尾スラッシュがあってもリンクは壊れない")
    void trailingSlashInBaseUrlIsNormalised() {
        listener("https://lab.4510.be/ec/", "").onOrderEvent(
                new OrderPlacedEvent(5L, "guest@example.com", TOTAL, TOKEN));

        assertThat(sender.only().getText()).contains("https://lab.4510.be/ec/#/orders/guest/5/" + TOKEN);
    }

    @Test
    @DisplayName("base-url が未設定なら、壊れたURLではなく手入力用の注文番号とトークンを載せる")
    void withoutBaseUrlTheTokenIsStillDelivered() {
        listener("", "").onOrderEvent(new OrderPlacedEvent(5L, "guest@example.com", TOTAL, TOKEN));

        String text = sender.only().getText();
        assertThat(text).contains(TOKEN).contains("注文番号: 5");
        assertThat(text).doesNotContain("http");   // localhost 等のでたらめなURLを送らない
    }

    @Test
    @DisplayName("支払い完了メールにも照会リンクが載る（発送までは注文に用がある）")
    void guestPaidMailAlsoCarriesTheLookupLink() {
        listener("https://lab.4510.be/ec", "").onOrderEvent(
                new OrderPaidEvent(5L, "guest@example.com", TOTAL, TOKEN, "stripe"));

        assertThat(sender.only().getText())
                .contains("https://lab.4510.be/ec/#/orders/guest/5/" + TOKEN);
    }

    // --- トークンを載せてはいけない先 ------------------------------------------

    @Test
    @DisplayName("会員注文のメールにはトークンもリンクも載らない（注文履歴から辿れる）")
    void memberMailHasNoToken() {
        listener("https://lab.4510.be/ec", "").onOrderEvent(
                new OrderPlacedEvent(5L, "member@example.com", TOTAL, null));

        String text = sender.only().getText();
        assertThat(text).doesNotContain("orders/guest").doesNotContain("照会");
    }

    @Test
    @DisplayName("管理者への受注控えにはトークンを転送しない")
    void adminCopyNeverContainsTheToken() {
        listener("https://lab.4510.be/ec", "shop@example.com").onOrderEvent(
                new OrderPaidEvent(5L, "guest@example.com", TOTAL, TOKEN, "stripe"));

        SimpleMailMessage toGuest = sender.to("guest@example.com");
        SimpleMailMessage toAdmin = sender.to("shop@example.com");
        assertThat(toGuest.getText()).contains(TOKEN);
        assertThat(toAdmin.getSubject()).startsWith("[受注]");
        assertThat(toAdmin.getText()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("キャンセル・失効の通知にはトークンを載せない（もう注文に用が無い）")
    void closedOrderMailsCarryNoToken() {
        MailOrderEventListener listener = listener("https://lab.4510.be/ec", "");

        listener.onOrderEvent(new OrderCancelledEvent(5L, "guest@example.com", TOTAL, TOKEN, "PENDING"));
        listener.onOrderEvent(new OrderExpiredEvent(6L, "guest@example.com", TOTAL, TOKEN));

        assertThat(sender.sent).hasSize(2)
                .allSatisfy(mail -> assertThat(mail.getText()).doesNotContain(TOKEN));
    }

    // --- 受信者が居ない場合 ----------------------------------------------------

    @Test
    @DisplayName("連絡先が無い注文では送信しない（宛先なしで送って例外にしない）")
    void noRecipientMeansNoMail() {
        listener("https://lab.4510.be/ec", "").onOrderEvent(
                new OrderPlacedEvent(5L, null, TOTAL, TOKEN));

        assertThat(sender.sent).isEmpty();
    }

    // --- helpers --------------------------------------------------------------

    private MailOrderEventListener listener(String baseUrl, String adminEmail) {
        return new MailOrderEventListener(new StaticProvider<>(sender),
                "no-reply@example.com", adminEmail, baseUrl, 30);
    }

    /** 送信された {@link SimpleMailMessage} を溜めるだけの JavaMailSender。 */
    static class CapturingMailSender extends JavaMailSenderImpl {

        final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            sent.add(simpleMessage);
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            sent.addAll(List.of(simpleMessages));
        }

        SimpleMailMessage only() {
            assertThat(sent).hasSize(1);
            return sent.get(0);
        }

        SimpleMailMessage to(String recipient) {
            return sent.stream()
                    .filter(m -> m.getTo() != null && List.of(m.getTo()).contains(recipient))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No mail addressed to " + recipient));
        }
    }

    /**
     * 常に同じインスタンスを返す {@link ObjectProvider}。本番では SMTP 未設定でも起動できる
     * ように {@code ObjectProvider} 経由で受けているので、テストでもその型で渡す。
     */
    record StaticProvider<T>(T instance) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }
}
