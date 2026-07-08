package com.prodigalgal.ircs.content.config;

import com.prodigalgal.ircs.common.readiness.AutoStartGate;
import com.prodigalgal.ircs.common.readiness.AutoStartGateHealthIndicator;
import java.util.List;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class ContentAutoStartGateHealthConfiguration {

    @Bean
    HealthIndicator autoStartGateHealthIndicator(Environment environment) {
        return new AutoStartGateHealthIndicator(environment, List.of(
                new AutoStartGate("content-listener", "app.content.listener.enabled",
                        "APP_CONTENT_LISTENER_ENABLED", false),
                new AutoStartGate("content-config-listener", "app.content.config-listener.enabled",
                        "APP_CONTENT_CONFIG_LISTENER_ENABLED", false),
                new AutoStartGate("content-maintenance-gate-listener", "app.content.maintenance-gate-listener.enabled",
                        "APP_CONTENT_MAINTENANCE_GATE_LISTENER_ENABLED", false),
                new AutoStartGate("content-resolver-preset-seed", "app.content.resolver-preset-seed.enabled",
                        "APP_CONTENT_RESOLVER_PRESET_SEED_ENABLED", true)
        ));
    }
}
