package com.example.ecapi.service;

import com.example.ecapi.domain.AppSetting;
import com.example.ecapi.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 店名とロゴ。税や送料と同じく、管理画面から<strong>再デプロイなしで</strong>変えられる。
 *
 * <h2>店名とロゴを別々に持つ理由</h2>
 * 「ロゴ画像を出す店」と「文字で店名を出す店」の両方があるため。ロゴが未設定なら
 * <strong>店名の文字列がそのまま看板になる</strong>ので、画像を用意していない店でも
 * ヘッダが空にならない。逆にロゴを設定しても店名は捨てない——{@code <img>} の alt、
 * ページタイトル、通知メールの差出人名に要るので、店名は常に持っておく。
 *
 * <p>環境変数の値は<strong>初期値</strong>にすぎない。一度でも管理画面から保存されたら、
 * 以後はDBの値が優先される（内税/外税・送料と同じ扱い）。
 */
@Service
public class BrandingSettings {

    static final String KEY_NAME = "store.name";
    static final String KEY_LOGO = "store.logo-url";

    private final AppSettingRepository repository;
    private final String defaultName;
    private final String defaultLogoUrl;

    public BrandingSettings(AppSettingRepository repository,
                            @Value("${app.store.name:和雑貨 みやび}") String defaultName,
                            @Value("${app.store.logo-url:images/brand/miyabi-logo-premium.svg}")
                            String defaultLogoUrl) {
        this.repository = repository;
        this.defaultName = defaultName;
        this.defaultLogoUrl = defaultLogoUrl;
    }

    @Transactional(readOnly = true)
    public String name() {
        return repository.findById(KEY_NAME)
                .map(AppSetting::getValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultName);
    }

    /**
     * ロゴのURL。空文字なら「ロゴなし＝店名を文字で出す」。
     *
     * <p>⚠ 保存値が空文字であることと、設定が無いことは<strong>別</strong>。
     * 「ロゴを外した」という意思表示を、既定値へのフォールバックで打ち消さないよう、
     * 行の有無で判定している。
     */
    @Transactional(readOnly = true)
    public String logoUrl() {
        return repository.findById(KEY_LOGO)
                .map(AppSetting::getValue)
                .orElse(defaultLogoUrl);
    }

    @Transactional
    public void setName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new com.example.ecapi.exception.BadRequestException("店名を入力してください");
        }
        repository.save(new AppSetting(KEY_NAME, trimmed));
    }

    /** ロゴのURLを設定する。空文字を渡すと「ロゴなし」になる（設定行は残る）。 */
    @Transactional
    public void setLogoUrl(String url) {
        repository.save(new AppSetting(KEY_LOGO, url == null ? "" : url.trim()));
    }
}
