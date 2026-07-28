package com.example.ecapi.payment;

import com.example.ecapi.exception.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 登録済み {@link PaymentProvider} の名寄せ。id → 実装の解決と、
 * 「有効な決済手段の一覧」の提供を担う。
 */
@Component
public class PaymentProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderRegistry.class);

    private final Map<String, PaymentProvider> byId = new LinkedHashMap<>();
    private final String configuredDefault;

    public PaymentProviderRegistry(ObjectProvider<PaymentProvider> provider, PaymentProperties properties) {
        this.configuredDefault = properties.getDefaultProvider();
        provider.stream().forEach(p -> {
            PaymentProvider clash = byId.putIfAbsent(p.id(), p);
            if (clash != null) {
                // id はURLとDBに焼き付くので、重複は起動時に落とす。実行時に「どちらが呼ばれるか
                // 分からない」状態のまま決済を受け付けるほうがはるかに危険。
                throw new IllegalStateException("Duplicate PaymentProvider id '" + p.id() + "': "
                        + clash.getClass().getName() + " and " + p.getClass().getName());
            }
        });
        log.info("Payment providers registered: {} (enabled: {})",
                byId.keySet(), enabled().stream().map(PaymentProvider::id).toList());
    }

    /** 設定が揃っていて実際に使える決済手段だけ。 */
    public List<PaymentProvider> enabled() {
        return byId.values().stream().filter(PaymentProvider::isEnabled).toList();
    }

    public boolean anyEnabled() {
        return byId.values().stream().anyMatch(PaymentProvider::isEnabled);
    }

    public Optional<PaymentProvider> find(String id) {
        return Optional.ofNullable(id).map(byId::get);
    }

    /**
     * 決済手段を解決する。{@code id} が null/空なら既定を使う。
     *
     * @throws BadRequestException 未知の id、または無効化されている手段を指定された場合
     */
    public PaymentProvider require(String id) {
        String wanted = (id == null || id.isBlank()) ? defaultId() : id;
        if (wanted == null) {
            throw new BadRequestException("No payment provider is configured. "
                    + "Set STRIPE_SECRET_KEY, or enable app.payment.bank-transfer.enabled.");
        }
        PaymentProvider provider = byId.get(wanted);
        if (provider == null) {
            throw new BadRequestException("Unknown payment provider '" + wanted + "'. Available: "
                    + enabled().stream().map(PaymentProvider::id).toList());
        }
        if (!provider.isEnabled()) {
            throw new BadRequestException("Payment provider '" + wanted + "' is not configured.");
        }
        return provider;
    }

    /**
     * 明示設定された既定、無ければ有効なうちの最初の1つ。1つも無ければ null。
     *
     * <p>既定に指定された手段が<em>無効</em>な場合も有効なものへ落とす。既定は
     * {@code stripe} だが、Stripeキー未設定の環境（デモ・ローカル）で
     * 「決済手段を指定しない呼び出しが全部エラー」になると使い物にならないため。
     */
    private String defaultId() {
        if (!configuredDefault.isBlank()
                && find(configuredDefault).filter(PaymentProvider::isEnabled).isPresent()) {
            return configuredDefault;
        }
        return enabled().stream().map(PaymentProvider::id).findFirst().orElse(null);
    }
}
