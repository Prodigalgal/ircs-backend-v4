package com.prodigalgal.ircs.notification.mail;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditEvent;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

@Slf4j
class MailSendHistoryCleanupRunner implements ApplicationRunner {

    static final String JOB_TYPE = "maintenance-runner";
    static final String JOB_NAME = "notification.mail-send-history.cleanup";
    static final String REAL_EXECUTE_REFUSAL = "real cleanup refused: APP_MAIL_SEND_HISTORY_CLEANUP_EXECUTE_ENABLED must be true";

    private final boolean enabled;
    private final boolean dryRun;
    private final boolean executeEnabled;
    private final int retentionDays;
    private final List<String> statuses;
    private final int batchSize;
    private final int maxBatches;
    private final int rateLimitDelayMs;
    private final boolean exitOnComplete;
    private final JdbcMailSendHistoryCleanupService cleanupService;
    private final WorkerJobAuditWriter auditWriter;
    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean clusterLockEnabled;
    private final Duration clusterLockTtl;
    private final ConfigurableApplicationContext applicationContext;

    MailSendHistoryCleanupRunner(
            boolean enabled,
            boolean dryRun,
            boolean executeEnabled,
            int retentionDays,
            String statuses,
            int batchSize,
            int maxBatches,
            int rateLimitDelayMs,
            boolean exitOnComplete,
            JdbcMailSendHistoryCleanupService cleanupService,
            WorkerJobAuditWriter auditWriter,
            DistributedLockManager lockManager,
            String applicationName,
            String configuredWorkerId,
            boolean clusterLockEnabled,
            String clusterLockTtl,
            ConfigurableApplicationContext applicationContext) {
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.executeEnabled = executeEnabled;
        this.retentionDays = retentionDays;
        this.statuses = parseStatuses(statuses);
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.rateLimitDelayMs = rateLimitDelayMs;
        this.exitOnComplete = exitOnComplete;
        this.cleanupService = cleanupService;
        this.auditWriter = auditWriter == null ? WorkerJobAuditWriter.noop() : auditWriter;
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.clusterLockEnabled = clusterLockEnabled;
        this.clusterLockTtl = parseDuration(clusterLockTtl, Duration.ofMinutes(10));
        this.applicationContext = applicationContext;
    }

    MailSendHistoryCleanupRunner(
            boolean enabled,
            boolean dryRun,
            boolean executeEnabled,
            int retentionDays,
            String statuses,
            int batchSize,
            int maxBatches,
            int rateLimitDelayMs,
            boolean exitOnComplete,
            JdbcMailSendHistoryCleanupService cleanupService,
            WorkerJobAuditWriter auditWriter,
            ConfigurableApplicationContext applicationContext) {
        this(
                enabled,
                dryRun,
                executeEnabled,
                retentionDays,
                statuses,
                batchSize,
                maxBatches,
                rateLimitDelayMs,
                exitOnComplete,
                cleanupService,
                auditWriter,
                null,
                "ircs-notification-worker",
                "local-test",
                false,
                "PT10M",
                applicationContext);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (!clusterLockEnabled) {
            runLocked();
            return;
        }
        if (lockManager == null) {
            throw new IllegalStateException("notification distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        if (lockManager.callWithLock(profile.keyPrefix() + "notification:mail-send-history-cleanup",
                workerId,
                clusterLockTtl,
                () -> {
                    runLocked();
                    return true;
                }).isEmpty()) {
            log.debug("Notification mail send history cleanup skipped: distributed lock is held by another instance");
        }
    }

    private void runLocked() {
        Instant startedAt = Instant.now();
        if (!dryRun && !executeEnabled) {
            RuntimeException refusal = new IllegalStateException(REAL_EXECUTE_REFUSAL);
            auditWriter.record(WorkerJobAuditEvent.failed(
                    JOB_TYPE,
                    JOB_NAME,
                    "execute-gate-refused",
                    Duration.between(startedAt, Instant.now()),
                    refusal));
            log.warn("Notification mail send history cleanup refused: {}", REAL_EXECUTE_REFUSAL);
            exitIfRequested(1);
            return;
        }
        MailSendHistoryCleanupResult result = cleanupService.cleanup(
                retentionDays,
                statuses,
                batchSize,
                maxBatches,
                dryRun,
                rateLimitDelayMs);
        log.info(
                "Notification mail send history cleanup result: success={}, dryRun={}, cutoff={}, candidateRows={}, deletedRows={}, batches={}, reason={}",
                result.success(),
                result.dryRun(),
                result.cutoff(),
                result.candidateRows(),
                result.deletedRows(),
                result.batches(),
                result.reason());
        recordAudit(startedAt, result);
        exitIfRequested(result.success() ? 0 : 1);
    }

    private void recordAudit(Instant startedAt, MailSendHistoryCleanupResult result) {
        Duration duration = Duration.between(startedAt, Instant.now());
        String correlationId = result.dryRun() ? "dry-run" : "execute";
        if (result.success()) {
            auditWriter.record(WorkerJobAuditEvent.succeeded(
                    JOB_TYPE,
                    JOB_NAME,
                    correlationId,
                    duration));
            return;
        }
        auditWriter.record(WorkerJobAuditEvent.failed(
                JOB_TYPE,
                JOB_NAME,
                correlationId,
                duration,
                new IllegalStateException(result.reason())));
    }

    private void exitIfRequested(int code) {
        if (!exitOnComplete) {
            return;
        }
        SpringApplication.exit(applicationContext, () -> code);
        System.exit(code);
    }

    private static List<String> parseStatuses(String statuses) {
        if (statuses == null || statuses.isBlank()) {
            return List.of();
        }
        return Arrays.stream(statuses.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            Duration duration = Duration.parse(trimmed);
            return duration.isPositive() ? duration : fallback;
        } catch (RuntimeException ignored) {
            // Continue with compact duration suffix parsing.
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith("ms")) {
                return positive(Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2))), fallback);
            }
            if (lower.endsWith("s")) {
                return positive(Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1))), fallback);
            }
            if (lower.endsWith("m")) {
                return positive(Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1))), fallback);
            }
            if (lower.endsWith("h")) {
                return positive(Duration.ofHours(Long.parseLong(lower.substring(0, lower.length() - 1))), fallback);
            }
        } catch (RuntimeException ignored) {
            return fallback;
        }
        return fallback;
    }

    private static Duration positive(Duration duration, Duration fallback) {
        return duration != null && duration.isPositive() ? duration : fallback;
    }
}
