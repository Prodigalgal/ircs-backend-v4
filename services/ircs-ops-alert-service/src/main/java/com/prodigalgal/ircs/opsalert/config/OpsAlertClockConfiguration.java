package com.prodigalgal.ircs.opsalert.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpsAlertClockConfiguration {

    @Bean
    Clock opsAlertClock() {
        return Clock.systemUTC();
    }
}
