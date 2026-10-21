package com.example.ecapi.config;

import com.example.ecapi.security.JwtAuthenticationFilter;
import com.example.ecapi.security.SecurityErrorResponder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorResponder securityErrors;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          SecurityErrorResponder securityErrors) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityErrors = securityErrors;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/error",
                                "/favicon.ico",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/assets/**",
                                "/api/auth/**",
                                // 金額の見積もりと送料設定。何も作らず何も明かさないので公開でよい
                                // （買い手が注文前に送料・クーポンを確認できないと意味がない）。
                                "/api/checkout/**",
                                // ヘッダの看板。画面が最初に描くものなので認証を要求しない
                                // （401 だと未ログインの買い手にはヘッダが空のまま見える）。
                                "/api/store/**",
                                "/manifest.json",
                                "/api/orders/guest-checkout",
                                "/api/orders/guest/**",
                                "/api/payments/guest/**",
                                // 決済手段プラグインごとの Webhook。認証はプラグイン側の署名検証が担う。
                                "/api/payments/*/webhook",
                                // 案内ページ。閲覧権限はサービス層でゲストトークン／ログインを検証する。
                                "/api/payments/*/instructions",
                                "/api/payments/config",
                                "/api/payments/success",
                                "/api/payments/cancel",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/h2-console/**",
                                "/actuator/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**", "/api/tax/**")
                        .permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // 未認証は 401・権限不足は 403 を、他のエラーと同じ形の JSON で返す。
                // 既定のままだと認証機構を登録していない構成では**未認証にも 403・本文は空**で、
                // 「ログインすれば入れる人」を「入れない人」として追い返すことになる。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrors)
                        .accessDeniedHandler(securityErrors))
                // allow H2 console frames in dev
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
