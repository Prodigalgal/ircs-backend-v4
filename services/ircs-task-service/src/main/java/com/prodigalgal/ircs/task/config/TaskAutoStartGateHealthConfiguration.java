package com.prodigalgal.ircs.task.config;

import com.prodigalgal.ircs.common.readiness.AutoStartGate;
import com.prodigalgal.ircs.common.readiness.AutoStartGateHealthIndicator;
import java.util.List;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class TaskAutoStartGateHealthConfiguration {

    @Bean
    HealthIndicator autoStartGateHealthIndicator(Environment environment) {
        return new AutoStartGateHealthIndicator(environment, List.of(
                new AutoStartGate("task-default-seed", "app.task.default-seed.enabled",
                        "APP_TASK_DEFAULT_SEED_ENABLED", true),
                new AutoStartGate("task-scheduler", "app.task.scheduler.enabled",
                        "APP_TASK_SCHEDULER_ENABLED", true),
                new AutoStartGate("task-watchdog", "app.task.watchdog.enabled",
                        "APP_TASK_WATCHDOG_ENABLED", false),
                new AutoStartGate("task-queue-listener", "app.task.queue.listener-enabled",
                        "APP_TASK_QUEUE_LISTENER_ENABLED", false),
                new AutoStartGate("task-snapshot-flush", "app.task.snapshot.flush.enabled",
                        "APP_TASK_SNAPSHOT_FLUSH_ENABLED", false)
        ));
    }
}
