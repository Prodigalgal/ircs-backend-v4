package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.common.readiness.AutoStartGate;
import com.prodigalgal.ircs.common.readiness.AutoStartGateHealthIndicator;
import java.util.List;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class ScraperAutoStartGateHealthConfiguration {

    @Bean
    HealthIndicator autoStartGateHealthIndicator(Environment environment) {
        return new AutoStartGateHealthIndicator(environment, List.of(
                new AutoStartGate("scraper-task-queue-listener", "app.scraper.task-queue.listener-enabled",
                        "APP_SCRAPER_TASK_QUEUE_LISTENER_ENABLED", false)
        ));
    }
}
