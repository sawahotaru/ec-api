package com.example.ecapi.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 決済手段によらず共通の設定（{@code app.payment.*}）。
 * 通貨は「Stripeの設定」ではなくストアの属性なので、業者別の設定から引き上げてある。
 */
@Component
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /** ISO 4217（小文字）。JPY のように補助単位を持たない通貨も想定。 */
    private String currency = "jpy";

    /** 決済手段が明示されなかったときに使う id。空なら「有効な最初の1つ」。 */
    private String defaultProvider = "";

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }
}
