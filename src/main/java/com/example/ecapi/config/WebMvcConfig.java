package com.example.ecapi.config;

import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.privacy.DemoReadOnlyInterceptor;
import com.example.ecapi.security.RateLimitInterceptor;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded product images from disk at {@code /images/uploads/**}.
 *
 * <p>The seeded catalog images live inside the jar under {@code static/images/products/},
 * which the default handler already covers. Uploads cannot go there — the jar is
 * read-only and would be replaced on the next deploy anyway — so they get their own
 * directory outside the app, mapped here.
 *
 * <p>Caching is long on purpose: {@link ProductImageStorage} gives every upload a fresh
 * random filename, so replacing a product's image changes the URL. Nothing is ever
 * served stale, and browsers can keep the old file until it falls out of cache.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProductImageStorage storage;
    private final DemoReadOnlyInterceptor demoReadOnly;
    private final RateLimitInterceptor rateLimit;

    public WebMvcConfig(ProductImageStorage storage, DemoReadOnlyInterceptor demoReadOnly,
                        RateLimitInterceptor rateLimit) {
        this.storage = storage;
        this.demoReadOnly = demoReadOnly;
        this.rateLimit = rateLimit;
    }

    /**
     * 門番は2段。<strong>役割が違うので両方要る</strong>。
     *
     * <ol>
     *   <li>{@link RateLimitInterceptor} — 送信元ごとの回数制限。API 全体の書き込みに当てる。
     *       買い物側（在庫の引当・注文行・会員登録）が<strong>無制限に叩けた</strong>ことが、
     *       「未認証で店を売り切れにできる」状態の入口だった。</li>
     *   <li>{@link DemoReadOnlyInterceptor} — 公開デモでの管理側の書き込み禁止。
     *       {@code /api/admin/**} だけに当てる。</li>
     * </ol>
     *
     * <p>⚠ 回数制限から {@code /api/payments/*&#47;webhook} を外してある。決済事業者から届く
     * 未認証の正当な要求で、認証は署名検証が担う。ここで 429 を返すと支払い確認を落とす
     * （しかも落ちたことは注文が PENDING のままになる形でしか現れない）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/payments/*/webhook");
        registry.addInterceptor(demoReadOnly).addPathPatterns("/api/admin/**");
    }


    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/" + ProductImageStorage.URL_PREFIX + "**")
                .addResourceLocations(storage.directory().toUri().toString())
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }
}
