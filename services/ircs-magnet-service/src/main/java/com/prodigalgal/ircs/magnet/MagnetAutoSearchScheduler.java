package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class MagnetAutoSearchScheduler {

    private static final int MAX_BATCH_SIZE = 100;
    private static final Duration DEFAULT_COOLDOWN = Duration.ofHours(12);

    private final JdbcMagnetRepository magnetRepository;
    private final MagnetQueryService magnetQueryService;
    private final RuntimeConfigService runtimeConfig;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-magnet-auto-search-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.magnet.auto-search.enabled:false}")
    private boolean enabledByDeployment;

    @Value("${app.magnet.auto-search.batch-size:10}")
    private int batchSizeByDeployment;

    @Value("${app.magnet.auto-search.cooldown:PT12H}")
    private Duration cooldownByDeployment;

    MagnetAutoSearchScheduler(
            JdbcMagnetRepository magnetRepository,
            MagnetQueryService magnetQueryService,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider) {
        this.magnetRepository = magnetRepository;
        this.magnetQueryService = magnetQueryService;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
    }

    @Scheduled(
            initialDelayString = "${app.magnet.auto-search.initial-delay-ms:60000}",
            fixedDelayString = "${app.magnet.auto-search.fixed-delay-ms:300000}")
    void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, this::runScheduledBatch, log, "magnet.auto-search.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    void runScheduledBatch() {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            runBatch();
        } catch (RuntimeException ex) {
            log.warn("Magnet auto search batch failed: {}", ex.getMessage());
        } finally {
            running.set(false);
        }
    }

    int runBatch() {
        List<UUID> candidates = magnetRepository.findAutoSearchCandidates(
                batchSize(),
                Instant.now().minus(cooldown()));
        int triggered = 0;
        for (UUID unifiedVideoId : candidates) {
            try {
                magnetQueryService.enqueueAutomaticSearch(unifiedVideoId);
                triggered++;
            } catch (RuntimeException ex) {
                log.warn("Magnet auto search failed: unifiedVideoId={}, error={}", unifiedVideoId, ex.getMessage());
            }
        }
        if (triggered > 0) {
            log.info("Triggered magnet auto search: count={}", triggered);
        }
        return triggered;
    }

    private boolean enabled() {
        return runtimeConfig == null
                ? enabledByDeployment
                : runtimeConfig.booleanValue("app.magnet.auto-search.enabled", enabledByDeployment);
    }

    private int batchSize() {
        int fallback = Math.max(1, Math.min(MAX_BATCH_SIZE, batchSizeByDeployment));
        return runtimeConfig == null
                ? fallback
                : runtimeConfig.boundedIntValue("app.magnet.auto-search.batch-size", fallback, 1, MAX_BATCH_SIZE);
    }

    private Duration cooldown() {
        Duration fallback = positive(cooldownByDeployment, DEFAULT_COOLDOWN);
        Duration value = runtimeConfig == null
                ? fallback
                : runtimeConfig.positiveDurationValue("app.magnet.auto-search.cooldown", fallback);
        return positive(value, fallback);
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
