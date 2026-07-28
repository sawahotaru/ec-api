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
