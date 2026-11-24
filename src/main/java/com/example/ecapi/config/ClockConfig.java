package com.example.ecapi.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 時刻の判定（支払いの期限など）を、テストで進められるように {@link Clock} を差し替え可能にする。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
