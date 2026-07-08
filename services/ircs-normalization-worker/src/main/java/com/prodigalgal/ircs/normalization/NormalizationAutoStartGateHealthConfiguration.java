package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.readiness.AutoStartGate;
import com.prodigalgal.ircs.common.readiness.AutoStartGateHealthIndicator;
import java.util.List;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class NormalizationAutoStartGateHealthConfiguration {

    @Bean
    HealthIndicator autoStartGateHealthIndicator(Environment environment) {
        return new AutoStartGateHealthIndicator(environment, List.of(
                new AutoStartGate("normalization-llm-cleaning-work-queue-worker",
                        "app.normalization.llm-cleaning.work-queue.worker.enabled",
                        "APP_NORMALIZATION_LLM_CLEANING_WORK_QUEUE_WORKER_ENABLED", false),
                new AutoStartGate("normalization-config-listener", "app.normalization.config-listener.enabled",
                        "APP_NORMALIZATION_CONFIG_LISTENER_ENABLED", false)
        ));
    }
}
