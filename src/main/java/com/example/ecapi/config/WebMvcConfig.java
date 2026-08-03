package com.example.ecapi.config;

import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.privacy.DemoReadOnlyInterceptor;
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

    public WebMvcConfig(ProductImageStorage storage, DemoReadOnlyInterceptor demoReadOnly) {
        this.storage = storage;
        this.demoReadOnly = demoReadOnly;
    }

    /**
     * 読み取り専用デモの門番。パターンは {@code /api/admin/**} だけに当てる
     * （買い物側の書き込みは止めない）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(demoReadOnly).addPathPatterns("/api/admin/**");
    }


    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/" + ProductImageStorage.URL_PREFIX + "**")
                .addResourceLocations(storage.directory().toUri().toString())
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }
}
