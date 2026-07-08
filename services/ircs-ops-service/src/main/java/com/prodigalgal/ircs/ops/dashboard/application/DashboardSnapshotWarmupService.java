package com.prodigalgal.ircs.ops.dashboard.application;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardSnapshotWarmupService {

    private static final String ENABLED_KEY = "app.ops.dashboard.snapshot.enabled";
    private static final String REFRESH_INTERVAL_KEY = "app.ops.dashboard.snapshot.refresh-interval";
    private static final String REFRESH_BUDGET_KEY = "app.ops.dashboard.snapshot.refresh-budget";
    private static final String DEFAULT_TASK_RUNTIME_LIMIT_KEY = "app.ops.dashboard.snapshot.default-task-runtime-limit";

    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_REFRESH_BUDGET = Duration.ofSeconds(3);
    private static final int DEFAULT_TASK_RUNTIME_LIMIT = 50;

    private final DashboardQueryService dashboardQueryService;
    private final RuntimeConfigService runtimeConfig;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-dashboard-warmup-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Instant nextWarmupAt = Instant.EPOCH;

    @Scheduled(
            initialDelayString = "${app.ops.dashboard.snapshot.initial-delay-ms:5000}",
            fixedDelayString = "${app.ops.dashboard.snapshot.tick-delay-ms:2000}")
    public void warmupOnSchedule() {
        ScheduledTriggers.submit(triggerExecutor, this::warmupIfDue, log, "ops.dashboard.snapshot.warmup");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void warmupIfDue() {
        if (!enabled() || Instant.now().isBefore(nextWarmupAt)) {
            return;
        }
        warmupNow();
    }

    void warmupNow() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Instant startedAt = Instant.now();
        try {
            int warmedBlocks = dashboardQueryService.warmAnalysisBlocks(
                    14,
                    taskRuntimeLimit(),
                    startedAt.plus(refreshBudget()));
            log.debug("Dashboard snapshot warmup completed: warmedBlocks={}", warmedBlocks);
        } catch (RuntimeException ex) {
            log.debug("Dashboard snapshot warmup failed: {}", ex.getMessage());
        } finally {
            running.set(false);
            nextWarmupAt = startedAt.plus(refreshInterval());
        }
    }

    private boolean enabled() {
        return runtimeConfig == null || runtimeConfig.booleanValue(ENABLED_KEY, true);
    }

    private Duration refreshInterval() {
        return runtimeConfig == null
                ? DEFAULT_REFRESH_INTERVAL
                : runtimeConfig.positiveDurationValue(REFRESH_INTERVAL_KEY, DEFAULT_REFRESH_INTERVAL);
    }

    private Duration refreshBudget() {
        return runtimeConfig == null
                ? DEFAULT_REFRESH_BUDGET
                : runtimeConfig.positiveDurationValue(REFRESH_BUDGET_KEY, DEFAULT_REFRESH_BUDGET);
    }

    private int taskRuntimeLimit() {
        return runtimeConfig == null
                ? DEFAULT_TASK_RUNTIME_LIMIT
                : runtimeConfig.boundedIntValue(
                        DEFAULT_TASK_RUNTIME_LIMIT_KEY,
                        DEFAULT_TASK_RUNTIME_LIMIT,
                        1,
                        500);
    }
}
