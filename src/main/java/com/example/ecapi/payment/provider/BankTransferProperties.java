package com.example.ecapi.payment.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code app.payment.bank-transfer.*}。既定は無効（デモ環境で勝手に選択肢に出さない）。 */
@Component
@ConfigurationProperties(prefix = "app.payment.bank-transfer")
public class BankTransferProperties {

    private boolean enabled = false;

    /** 案内ページに表示する振込先。改行区切りの自由記述。 */
    private String accountInfo = "〇〇銀行 〇〇支店 普通 1234567 カ）サンプルストア";

    /** 入金確認までの目安（案内文に埋め込む）。 */
    private String noteDays = "3";

    /** 案内の注記の最後に出す固定文（空なら出さない）。 */
    private String notice = "";

    /** 商品ごとの上限: 1 商品の在庫のうち、振込の取り置きが占めてよい割合（%）。 */
    private int maxHoldPercent = 20;

    /** 会員の件数: 会員 1 人あたりの、未払いの振込の注文の件数。 */
    private int maxPendingPerMember = 1;

    /** 送信元の回数: 同じ送信元が振込を選べる回数（24 時間）。 */
    private int maxPerSource = 3;

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public int getMaxHoldPercent() {
        return maxHoldPercent;
    }

    public void setMaxHoldPercent(int maxHoldPercent) {
        this.maxHoldPercent = maxHoldPercent;
    }

    public int getMaxPendingPerMember() {
        return maxPendingPerMember;
    }

    public void setMaxPendingPerMember(int maxPendingPerMember) {
        this.maxPendingPerMember = maxPendingPerMember;
    }

    public int getMaxPerSource() {
        return maxPerSource;
    }

    public void setMaxPerSource(int maxPerSource) {
        this.maxPerSource = maxPerSource;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAccountInfo() {
        return accountInfo;
    }

    public void setAccountInfo(String accountInfo) {
        this.accountInfo = accountInfo;
    }

    public String getNoteDays() {
        return noteDays;
    }

    public void setNoteDays(String noteDays) {
        this.noteDays = noteDays;
    }
}
