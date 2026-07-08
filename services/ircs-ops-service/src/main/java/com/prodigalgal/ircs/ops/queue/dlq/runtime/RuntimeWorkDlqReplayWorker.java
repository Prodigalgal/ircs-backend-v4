package com.prodigalgal.ircs.ops.queue.dlq.runtime;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkQueueCatalog;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkQueueDescriptor;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
class RuntimeWorkDlqReplayWorker {

    private static final String LEASE_NAME = "runtime-work-dlq-replay";
    private static final String CONSUMER_ID = "runtime-dlq-replayer";

    private final RuntimeWorkDlqService dlqService;
    private final ObjectProvider<ClusterLeaseService> clusterLeaseServiceProvider;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-runtime-dlq-replay-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger cursor = new AtomicInteger(0);

    @Value("${app.ops.runtime-dlq-replay.worker.enabled:true}")
    private boolean enabled;

    @Value("${app.ops.runtime-dlq-replay.worker.max-replay-attempts:3}")
    private int maxReplayAttempts;

    @Value("${app.ops.runtime-dlq-replay.worker.lease-ttl:PT30S}")
    private Duration leaseTtl;

    @Value("${app.ops.runtime-dlq-replay.worker.consumer-ttl:PT45S}")
    private Duration consumerTtl;

    @Value("${app.ops.runtime-dlq-replay.worker.task-types:}")
    private String configuredTaskTypes;

    RuntimeWorkDlqReplayWorker(
            RuntimeWorkDlqService dlqService,
            ObjectProvider<ClusterLeaseService> clusterLeaseServiceProvider) {
        this.dlqService = dlqService;
        this.clusterLeaseServiceProvider = clusterLeaseServiceProvider;
    }

    @Scheduled(
            initialDelayString = "${app.ops.runtime-dlq-replay.worker.initial-delay-ms:10000}",
            fixedDelayString = "${app.ops.runtime-dlq-replay.worker.fixed-delay-ms:1000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "ops.runtime-dlq-replay.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    int runOnce() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return 0;
        }
        ClusterLease lease = null;
        try {
            lease = acquireLease().orElse(null);
            if (lease == null && leaseService() != null) {
                return 0;
            }
            dlqService.heartbeatDlqConsumers(CONSUMER_ID, positiveOr(consumerTtl, Duration.ofSeconds(45)));
            int requeued = requeueRoundRobin();
            if (requeued > 0) {
                log.info("Runtime work DLQ replay requeued message: count={}", requeued);
            }
            return requeued;
        } catch (RuntimeException ex) {
            log.warn("Runtime work DLQ replay failed: {}", ex.getMessage());
            return 0;
        } finally {
            releaseLease(lease);
            running.set(false);
        }
    }

    private int requeueRoundRobin() {
        List<String> taskTypes = taskTypes();
        if (taskTypes.isEmpty()) {
            return 0;
        }
        int start = Math.floorMod(cursor.getAndIncrement(), taskTypes.size());
        for (int offset = 0; offset < taskTypes.size(); offset++) {
            String taskType = taskTypes.get((start + offset) % taskTypes.size());
            int affected = dlqService.requeueOne(taskType, maxReplayAttempts);
            if (affected > 0) {
                return affected;
            }
        }
        return 0;
    }

    private List<String> taskTypes() {
        if (!StringUtils.hasText(configuredTaskTypes)) {
            return RuntimeWorkQueueCatalog.descriptors().stream()
                    .map(RuntimeWorkQueueDescriptor::taskType)
                    .toList();
        }
        return Arrays.stream(StringUtils.commaDelimitedListToStringArray(configuredTaskTypes))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(taskType -> RuntimeWorkQueueCatalog.findByTaskType(taskType).isPresent())
                .distinct()
                .toList();
    }

    private Optional<ClusterLease> acquireLease() {
        ClusterLeaseService leaseService = leaseService();
        if (leaseService == null) {
            return Optional.empty();
        }
        return leaseService.tryAcquire(LEASE_NAME, CONSUMER_ID, positiveOr(leaseTtl, Duration.ofSeconds(30)));
    }

    private void releaseLease(ClusterLease lease) {
        if (lease == null) {
            return;
        }
        ClusterLeaseService leaseService = leaseService();
        if (leaseService == null) {
            return;
        }
        try {
            leaseService.release(lease);
        } catch (RuntimeException ex) {
            log.debug("Runtime work DLQ replay lease release failed: {}", ex.getMessage());
        }
    }

    private ClusterLeaseService leaseService() {
        return clusterLeaseServiceProvider == null ? null : clusterLeaseServiceProvider.getIfUnique();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
