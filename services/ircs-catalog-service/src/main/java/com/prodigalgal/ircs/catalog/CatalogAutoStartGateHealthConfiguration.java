package com.prodigalgal.ircs.catalog;

import com.prodigalgal.ircs.common.readiness.AutoStartGate;
import com.prodigalgal.ircs.common.readiness.AutoStartGateHealthIndicator;
import java.util.List;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class CatalogAutoStartGateHealthConfiguration {

    @Bean
    HealthIndicator autoStartGateHealthIndicator(Environment environment) {
        return new AutoStartGateHealthIndicator(environment, List.of(
                new AutoStartGate("catalog-default-seed", "app.catalog.default-seed.enabled",
                        "APP_CATALOG_DEFAULT_SEED_ENABLED", true)
        ));
    }
}
