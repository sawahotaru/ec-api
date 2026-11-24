package com.example.ecapi.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.payment.PaymentProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** テスト用以外のキーでは Stripe を無効にする。本物の形の値はテスト内で連結して作る。 */
class StripeKeyModeTest {

    private static final String LIVE_SK = "sk_" + "live_" + "dummy";
    private static final String LIVE_RK = "rk_" + "live_" + "dummy";
    private static final String PUB_TEST = "pk_" + "test_" + "x";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(StripePaymentProvider.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static StripePaymentProvider provider(String key, String whsec) {
        StripeProperties props = new StripeProperties();
        props.setSecretKey(key);
        props.setWebhookSecret(whsec);
        return new StripePaymentProvider(props, new PaymentProperties());
    }

    @ParameterizedTest
    @CsvSource(value = {"sk_test_x,true", "rk_test_x,true", "'',false"})
    void testKeysAreEnabled(String key, boolean expected) {
        assertThat(provider(key, "").isEnabled()).isEqualTo(expected);
    }

    @Test
    void nonTestKeysAreDisabled() {
        assertThat(provider(LIVE_SK, "").isEnabled()).isFalse();
        assertThat(provider(LIVE_RK, "").isEnabled()).isFalse();
        assertThat(provider(PUB_TEST, "").isEnabled()).isFalse();
    }

    @Test
    void liveShapedKeyLogsOneErrorWithoutTheKey() {
        provider(LIVE_SK, "whsec_unit_test_dummy").reportConfigOnStartup();

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).startsWith("STRIPE_KEY_NOT_TEST");
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("dummy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk_test_x", ""})
    void testKeyOrEmptyKeyLogsNoError(String key) {
        provider(key, "").reportConfigOnStartup();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    void unexpectedWebhookSecretShapeOnlyWarns() {
        provider("sk_test_x", "unexpected_value_dummy").reportConfigOnStartup();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("unexpected_value_dummy");
    }
}
