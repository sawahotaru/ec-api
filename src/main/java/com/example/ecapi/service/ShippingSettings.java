package com.example.ecapi.service;

import com.example.ecapi.domain.AppSetting;
import com.example.ecapi.repository.AppSettingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送料の設定。税率と同じく、管理画面から<strong>再デプロイなしで</strong>変えられる。
 *
 * <p>金額は商品価格と同じ流儀で読む（内税モードなら税込・外税モードなら税抜）。
 * 送料だけ別の流儀にすると、店頭表示の「送料 ¥600」と請求額が食い違う。
 *
 * <p>環境変数の値は<strong>初期値</strong>にすぎない。一度でも管理画面から保存されたら、
 * 以後はDBの値が優先される（税の内税/外税と同じ扱い）。
 */
@Service
public class ShippingSettings {

    private static final Logger log = LoggerFactory.getLogger(ShippingSettings.class);

    static final String KEY_FEE = "shipping.fee";
    static final String KEY_FREE_THRESHOLD = "shipping.free-threshold";

    private final AppSettingRepository repository;
    private final BigDecimal defaultFee;
    private final BigDecimal defaultFreeThreshold;

    public ShippingSettings(AppSettingRepository repository,
                            @Value("${app.shipping.fee:0}") BigDecimal defaultFee,
                            @Value("${app.shipping.free-threshold:0}") BigDecimal defaultFreeThreshold) {
        this.repository = repository;
        this.defaultFee = defaultFee;
        this.defaultFreeThreshold = defaultFreeThreshold;
    }

    /** 送料。0 なら送料を取らない設定。 */
    @Transactional(readOnly = true)
    public BigDecimal fee() {
        return read(KEY_FEE, defaultFee);
    }

    /** これ以上で送料無料になる金額。0 なら「無料になることはない」。 */
    @Transactional(readOnly = true)
    public BigDecimal freeThreshold() {
        return read(KEY_FREE_THRESHOLD, defaultFreeThreshold);
    }

    /**
     * 送料無料になるか。
     *
     * <p>判定は<strong>割引後</strong>の商品合計で行う。「5,000円以上で送料無料」は
     * 客が実際に払う額に対する約束と読むのが自然で、割引前で判定すると
     * 「3,000円のクーポンを使ったのに送料無料のまま」になる。
     */
    public boolean isFreeFor(BigDecimal itemsTotal) {
        BigDecimal threshold = freeThreshold();
        return threshold.signum() > 0 && itemsTotal.compareTo(threshold) >= 0;
    }

    @Transactional
    public void setFee(BigDecimal fee) {
        repository.save(new AppSetting(KEY_FEE, normalise(fee).toPlainString()));
    }

    @Transactional
    public void setFreeThreshold(BigDecimal threshold) {
        repository.save(new AppSetting(KEY_FREE_THRESHOLD, normalise(threshold).toPlainString()));
    }

    private BigDecimal read(String key, BigDecimal fallback) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .map(raw -> parse(key, raw, fallback))
                .orElse(normalise(fallback));
    }

    private BigDecimal parse(String key, String raw, BigDecimal fallback) {
        try {
            return normalise(new BigDecimal(raw));
        } catch (NumberFormatException e) {
            // 設定が壊れていても店を止めない。落ちるより、既定値で売れるほうがまし。
            log.warn("Setting {} is not a number ({}) — falling back to {}", key, raw, fallback);
            return normalise(fallback);
        }
    }

    /** 負の送料は無い。金額はすべて円単位（scale 2）に揃える。 */
    private BigDecimal normalise(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return v.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
