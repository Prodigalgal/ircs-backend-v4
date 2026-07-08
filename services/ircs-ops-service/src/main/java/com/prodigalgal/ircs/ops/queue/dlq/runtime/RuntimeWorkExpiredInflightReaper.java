package com.prodigalgal.ircs.ops.queue.dlq.runtime;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import com.prodigalgal.ircs.common.scheduling.ScheduledTriggers;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class RuntimeWorkExpiredInflightReaper {

    private static final String LEASE_NAME = "runtime-work-expired-inflight-reaper";
    private static final String OWNER_ID = "runtime-expired-inflight-reaper";

    private final ObjectProvider<RuntimeWorkQueue> runtimeWorkQueueProvider;
    private final ObjectProvider<ClusterLeaseService> clusterLeaseServiceProvider;
    private final RuntimeConfigService runtimeConfig;
    private final ExecutorService triggerExecutor =
            ScheduledTriggers.virtualThreadExecutor("ircs-runtime-expired-reaper-trigger-vt-");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger cursor = new AtomicInteger(0);

    RuntimeWorkExpiredInflightReaper(
            ObjectProvider<RuntimeWorkQueue> runtimeWorkQueueProvider,
            ObjectProvider<ClusterLeaseService> clusterLeaseServiceProvider,
            RuntimeConfigService runtimeConfig) {
        this.runtimeWorkQueueProvider = runtimeWorkQueueProvider;
        this.clusterLeaseServiceProvider = clusterLeaseServiceProvider;
        this.runtimeConfig = runtimeConfig;
    }

    @Scheduled(
            initialDelayString = "${app.ops.runtime-expired-inflight-reaper.initial-delay-ms:10000}",
            fixedDelayString = "${app.ops.runtime-expired-inflight-reaper.fixed-delay-ms:1000}")
    public void runScheduled() {
        ScheduledTriggers.submit(triggerExecutor, () -> runOnce(), log, "ops.runtime-expired-inflight-reaper.run");
    }

    @PreDestroy
    void shutdownTriggerExecutor() {
        triggerExecutor.shutdownNow();
    }

    public int runOnce() {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return 0;
        }
        ClusterLease lease = null;
        try {
            RuntimeWorkQueue queue = runtimeWorkQueueProvider == null ? null : runtimeWorkQueueProvider.getIfAvailable();
            if (queue == null) {
                return 0;
            }
            lease = acquireLease().orElse(null);
            if (lease == null && leaseService() != null) {
                return 0;
            }
            int requeued = requeueRoundRobin(queue);
            if (requeued > 0) {
                log.info("Runtime work expired inflight requeued: count={}", requeued);
            }
            return requeued;
        } catch (RuntimeException ex) {
            log.warn("Runtime work expired inflight reaper failed: {}", ex.getMessage());
            return 0;
        } finally {
            releaseLease(lease);
            running.set(false);
        }
    }

    private int requeueRoundRobin(RuntimeWorkQueue queue) {
        List<String> taskTypes = taskTypes();
        if (taskTypes.isEmpty()) {
            return 0;
        }
        int start = Math.floorMod(cursor.getAndIncrement(), taskTypes.size());
        int safeBatchSize = Math.max(1, runtimeConfig.intValue("app.ops.runtime-expired-inflight-reaper.batch-size", 50));
        for (int offset = 0; offset < taskTypes.size(); offset++) {
            String taskType = taskTypes.get((start + offset) % taskTypes.size());
            int affected = queue.requeueExpired(taskType, safeBatchSize);
            if (affected > 0) {
                return affected;
            }
        }
        return 0;
    }

    private List<String> taskTypes() {
        String configuredTaskTypes = runtimeConfig.stringValue("app.ops.runtime-expired-inflight-reaper.task-types", "");
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
        Duration leaseTtl = runtimeConfig.durationValue(
                "app.ops.runtime-expired-inflight-reaper.lease-ttl", Duration.ofSeconds(30));
        return leaseService.tryAcquire(LEASE_NAME, OWNER_ID, positiveOr(leaseTtl, Duration.ofSeconds(30)));
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
            log.debug("Runtime work expired inflight reaper lease release failed: {}", ex.getMessage());
        }
    }

    private ClusterLeaseService leaseService() {
        return clusterLeaseServiceProvider == null ? null : clusterLeaseServiceProvider.getIfUnique();
    }

    private boolean enabled() {
        return runtimeConfig.booleanValue("app.ops.runtime-expired-inflight-reaper.enabled", true);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
