package com.prodigalgal.ircs.ops.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpsClockConfiguration {

    @Bean
    Clock opsClock() {
        return Clock.systemUTC();
    }
}
