package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.readiness.AutoStartGate;
import com.prodigalgal.ircs.common.readiness.AutoStartGateHealthIndicator;
import java.util.List;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class StorageAutoStartGateHealthConfiguration {

    @Bean
    HealthIndicator autoStartGateHealthIndicator(Environment environment) {
        return new AutoStartGateHealthIndicator(environment, List.of(
                new AutoStartGate("storage-listener", "app.storage.listener.enabled",
                        "APP_STORAGE_LISTENER_ENABLED", false),
                new AutoStartGate("storage-config-listener", "app.storage.config-listener.enabled",
                        "APP_STORAGE_CONFIG_LISTENER_ENABLED", false),
                new AutoStartGate("storage-r2", "app.storage.r2.enabled",
                        "APP_STORAGE_R2_ENABLED", false),
                new AutoStartGate("storage-r2-work-queue-worker", "app.storage.r2.work-queue.worker.enabled",
                        "APP_STORAGE_R2_WORK_QUEUE_WORKER_ENABLED", false)
        ));
    }
}
