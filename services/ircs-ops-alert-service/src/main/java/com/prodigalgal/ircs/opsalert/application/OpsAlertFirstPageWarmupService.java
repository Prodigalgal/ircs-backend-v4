package com.prodigalgal.ircs.opsalert.application;

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
public class OpsAlertFirstPageWarmupService {

    private static final String ENABLED_KEY = "app.ops-alert.first-page-cache.warmup.enabled";
    private static final String REFRESH_INTERVAL_KEY = "app.ops-alert.first-page-cache.warmup.refresh-interval";
    private static final String PAGE_SIZE_KEY = "app.ops-alert.first-page-cache.warmup.page-size";

    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(15);
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final OpsAlertQueryService queryService;
    private final RuntimeConfigService runtimeConfig;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-ops-alert-warmup-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Instant nextWarmupAt = Instant.EPOCH;

    @Scheduled(
            initialDelayString = "${app.ops-alert.first-page-cache.warmup.initial-delay-ms:8000}",
            fixedDelayString = "${app.ops-alert.first-page-cache.warmup.tick-delay-ms:5000}")
    public void warmupOnSchedule() {
        ScheduledTriggers.submit(triggerExecutor, this::warmupIfDue, log, "ops-alert.first-page-warmup");
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
            int warmedPages = queryService.warmFirstPages(pageSize());
            log.debug("Ops-alert first-page warmup completed: warmedPages={}", warmedPages);
        } catch (RuntimeException ex) {
            log.debug("Ops-alert first-page warmup failed: {}", ex.getMessage());
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

    private int pageSize() {
        return runtimeConfig == null
                ? DEFAULT_PAGE_SIZE
                : runtimeConfig.boundedIntValue(PAGE_SIZE_KEY, DEFAULT_PAGE_SIZE, 1, 100);
    }
}
