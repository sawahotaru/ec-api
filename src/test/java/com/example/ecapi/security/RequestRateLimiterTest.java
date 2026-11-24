package com.example.ecapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回数カウンタ単体。HTTP を立てる {@link RateLimitTest} が「効いていること」を見るのに対し、
 * ここは<strong>数え方そのもの</strong>——枠と送信元が混ざらないこと、窓が明けること、
 * 無制限に落とせること——を固定する。
 */
class RequestRateLimiterTest {

    private final RequestRateLimiter limiter = new RequestRateLimiter();

    @Test
    @DisplayName("上限までは通り、超えたら残り秒数を返す")
    void allowsUpToTheLimitThenReports() {
        Duration window = Duration.ofSeconds(60);

        assertThat(limiter.acquire("b", "k", 2, window)).isZero();
        assertThat(limiter.acquire("b", "k", 2, window)).isZero();

        long retryAfter = limiter.acquire("b", "k", 2, window);
        assertThat(retryAfter).isPositive().isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("送信元が違えば枠も別（1人が全員を締め出さない）")
    void keysAreIndependent() {
        limiter.acquire("b", "attacker", 1, Duration.ofSeconds(60));
        assertThat(limiter.acquire("b", "attacker", 1, Duration.ofSeconds(60))).isPositive();

        // 巻き添えで他の客が止まるなら、制限そのものが攻撃手段になる
        assertThat(limiter.acquire("b", "shopper", 1, Duration.ofSeconds(60))).isZero();
    }

    @Test
    @DisplayName("枠が違えば別勘定（見積もりを連打しても注文の枠は減らない）")
    void bucketsAreIndependent() {
        limiter.acquire("quote", "k", 1, Duration.ofSeconds(60));
        assertThat(limiter.acquire("quote", "k", 1, Duration.ofSeconds(60))).isPositive();

        assertThat(limiter.acquire("checkout", "k", 1, Duration.ofSeconds(60))).isZero();
    }

    @Test
    @DisplayName("窓が明ければまた通る（恒久的な締め出しにしない）")
    void windowReopens() throws Exception {
        assertThat(limiter.acquire("b", "k", 1, Duration.ofMillis(100))).isZero();
        assertThat(limiter.acquire("b", "k", 1, Duration.ofMillis(100))).isPositive();

        Thread.sleep(150);

        assertThat(limiter.acquire("b", "k", 1, Duration.ofMillis(100))).isZero();
    }

    @Test
    @DisplayName("上限 0 以下はその枠だけ無制限（設定で個別に外せる）")
    void nonPositiveLimitDisablesTheBucket() {
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.acquire("b", "k", 0, Duration.ofSeconds(60))).isZero();
        }
    }

    @Test
    @DisplayName("枠に当たったときのログに、メールアドレス・IP アドレスを書かない（種類と短い要約だけ）")
    void logDoesNotContainTheSender() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            limiter.acquire("b", "user:secret-person@example.com", 1, Duration.ofSeconds(60));
            limiter.acquire("b", "user:secret-person@example.com", 1, Duration.ofSeconds(60));
            limiter.acquire("b", "ip:203.0.113.77", 1, Duration.ofSeconds(60));
            limiter.acquire("b", "ip:203.0.113.77", 1, Duration.ofSeconds(60));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(2);
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage())
                    .doesNotContain("secret-person").doesNotContain("example.com").doesNotContain("203.0.113");
        }
        assertThat(appender.list.get(0).getFormattedMessage()).contains("user:");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("ip:");
    }
}
