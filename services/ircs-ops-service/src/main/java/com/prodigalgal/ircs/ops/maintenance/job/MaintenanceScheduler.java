package com.prodigalgal.ircs.ops.maintenance.job;

import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceSchedulerService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class MaintenanceScheduler {

    private final MaintenanceSchedulerService schedulerService;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-maintenance-scheduler-trigger-vt-");

    @Scheduled(
            initialDelayString = "${app.ops.maintenance.scheduler.initial-delay-ms:60000}",
            fixedDelayString = "${app.ops.maintenance.scheduler.fixed-delay-ms:300000}")
    void tick() {
        ScheduledTriggers.submit(triggerExecutor, this::tickOnce, log, "ops.maintenance.tick");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void tickOnce() {
        try {
            schedulerService.scheduledTick();
        } catch (RuntimeException ex) {
            log.warn("Maintenance scheduler tick failed: {}", ex.getMessage());
        }
    }
}
