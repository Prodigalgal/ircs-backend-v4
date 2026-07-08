package com.prodigalgal.ircs.common.retention;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.time.ClockProviders;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class LogRetentionScheduler {

    static final String ENABLED_KEY = "app.log-retention.enabled";
    static final String DEFAULT_RETENTION_KEY = "app.log-retention.default-retention";
    static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    private final ObjectProvider<LogRetentionTarget> targetProvider;
    private final RuntimeConfigService runtimeConfig;
    private final Clock clock;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-log-retention-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LogRetentionScheduler(
            ObjectProvider<LogRetentionTarget> targetProvider,
            RuntimeConfigService runtimeConfig,
            ObjectProvider<Clock> clockProvider) {
        this.targetProvider = targetProvider;
        this.runtimeConfig = runtimeConfig;
        this.clock = ClockProviders.uniqueOrSystemUtc(clockProvider);
    }

    @Scheduled(
            initialDelayString = "${app.log-retention.initial-delay-ms:60000}",
            fixedDelayString = "${app.log-retention.fixed-delay-ms:3600000}")
    void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "common.log-retention.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    List<LogRetentionResult> runOnce() {
        if (!runtimeConfig.booleanValue(ENABLED_KEY, true) || !running.compareAndSet(false, true)) {
            return List.of();
        }
        try {
            List<LogRetentionResult> results = new ArrayList<>();
            for (LogRetentionTarget target : targetProvider.orderedStream().toList()) {
                purgeTarget(target).ifPresent(results::add);
            }
            return List.copyOf(results);
        } finally {
            running.set(false);
        }
    }

    private java.util.Optional<LogRetentionResult> purgeTarget(LogRetentionTarget target) {
        String targetId = target == null ? null : target.id();
        if (!StringUtils.hasText(targetId)) {
            return java.util.Optional.empty();
        }
        String normalizedTargetId = targetId.trim();
        if (!runtimeConfig.booleanValue(targetKey(normalizedTargetId, "enabled"), true)) {
            return java.util.Optional.empty();
        }
        Duration retention = retention(normalizedTargetId);
        Instant cutoff = clock.instant().minus(retention);
        try {
            LogRetentionResult result = target.deleteOlderThan(cutoff, retention);
            LogRetentionResult effective = result == null
                    ? new LogRetentionResult(normalizedTargetId, cutoff, retention, 0L)
                    : result;
            if (effective.deletedCount() > 0L) {
                log.info(
                        "Log retention purged target={}, cutoff={}, retention={}, deleted={}",
                        effective.targetId(),
                        effective.cutoff(),
                        effective.retention(),
                        effective.deletedCount());
            }
            return java.util.Optional.of(effective);
        } catch (RuntimeException ex) {
            log.warn("Log retention target {} failed: {}", normalizedTargetId, ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    private Duration retention(String targetId) {
        Duration globalRetention = runtimeConfig.positiveDurationValue(DEFAULT_RETENTION_KEY, DEFAULT_RETENTION);
        return runtimeConfig.positiveDurationValue(targetKey(targetId, "retention"), globalRetention);
    }

    static String targetKey(String targetId, String property) {
        return "app.log-retention.target." + targetId + "." + property;
    }
}
