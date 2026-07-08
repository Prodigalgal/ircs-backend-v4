package com.prodigalgal.ircs.ops.queue.dlq.runtime;

import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkQueueCatalog;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkQueueDescriptor;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class RuntimeWorkDlqService {

    private static final int DEFAULT_SAMPLE_LIMIT = 5;
    private static final int MAX_SAMPLE_LIMIT = 50;
    private static final int MAX_ACTION_LIMIT = 100;
    private static final int DEFAULT_MAX_REPLAY_ATTEMPTS = 3;
    private static final int PAYLOAD_PREVIEW_LIMIT = 500;
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(5);
    private static final String CACHE_TTL_CONFIG_KEY = "app.ops.runtime-dlq.cache-ttl";

    private final RuntimeWorkQueue workQueue;
    private final RuntimeConfigService runtimeConfig;
    private final Clock clock;
    private final ExecutorService queueReadExecutor;
    private final Map<Integer, CachedQueues> queueCache = new ConcurrentHashMap<>();

    public RuntimeWorkDlqService(RuntimeWorkQueue workQueue, RuntimeConfigService runtimeConfig, Clock clock) {
        this.workQueue = workQueue;
        this.runtimeConfig = runtimeConfig;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.queueReadExecutor = VirtualThreadExecutors.newPerTaskExecutor("ops-runtime-dlq-read-");
    }

    List<RuntimeWorkDlqQueueResponse> listQueues(int sampleLimit) {
        int safeSampleLimit = safeSampleLimit(sampleLimit);
        Duration ttl = cacheTtl();
        if (!cacheEnabled(ttl)) {
            return loadQueues(safeSampleLimit);
        }
        Instant now = Instant.now(clock);
        CachedQueues current = queueCache.get(safeSampleLimit);
        if (current != null && current.isFresh(now)) {
            return current.responses();
        }
        synchronized (queueCache) {
            current = queueCache.get(safeSampleLimit);
            if (current != null && current.isFresh(now)) {
                return current.responses();
            }
            List<RuntimeWorkDlqQueueResponse> loaded = loadQueues(safeSampleLimit);
            queueCache.put(safeSampleLimit, new CachedQueues(loaded, now.plus(ttl)));
            return loaded;
        }
    }

    RuntimeWorkDlqActionResponse requeue(String taskType, int limit, int maxReplayAttempts) {
        RuntimeWorkQueueDescriptor descriptor = descriptor(taskType);
        int safeLimit = safeActionLimit(limit);
        int safeMaxReplayAttempts = Math.max(1, maxReplayAttempts <= 0
                ? DEFAULT_MAX_REPLAY_ATTEMPTS
                : maxReplayAttempts);
        clearCache();
        int affected = workQueue.requeueDlq(descriptor.taskType(), safeLimit, safeMaxReplayAttempts);
        RuntimeWorkDlqQueueResponse queue = queueResponse(descriptor, DEFAULT_SAMPLE_LIMIT);
        clearCache();
        return new RuntimeWorkDlqActionResponse(
                descriptor.taskType(),
                "requeue",
                safeLimit,
                affected,
                safeMaxReplayAttempts,
                queue);
    }

    public int requeueOne(String taskType, int maxReplayAttempts) {
        RuntimeWorkQueueDescriptor descriptor = descriptor(taskType);
        clearCache();
        int affected = workQueue.requeueDlq(
                descriptor.taskType(),
                1,
                Math.max(1, maxReplayAttempts <= 0 ? DEFAULT_MAX_REPLAY_ATTEMPTS : maxReplayAttempts));
        clearCache();
        return affected;
    }

    void heartbeatDlqConsumers(String ownerId, java.time.Duration ttl) {
        if (!StringUtils.hasText(ownerId)) {
            return;
        }
        RuntimeWorkQueueCatalog.descriptors()
                .forEach(descriptor -> workQueue.heartbeatDlqConsumer(descriptor.taskType(), ownerId, ttl));
    }

    void clearCache() {
        queueCache.clear();
    }

    @PreDestroy
    void shutdown() {
        queueReadExecutor.shutdownNow();
    }

    private List<RuntimeWorkDlqQueueResponse> loadQueues(int sampleLimit) {
        List<CompletableFuture<RuntimeWorkDlqQueueResponse>> futures = RuntimeWorkQueueCatalog.descriptors().stream()
                .map(descriptor -> CompletableFuture.supplyAsync(
                        () -> safeQueueResponse(descriptor, sampleLimit),
                        queueReadExecutor))
                .toList();
        List<RuntimeWorkDlqQueueResponse> responses = new ArrayList<>(futures.size());
        for (CompletableFuture<RuntimeWorkDlqQueueResponse> future : futures) {
            responses.add(future.join());
        }
        return List.copyOf(responses);
    }

    private RuntimeWorkDlqQueueResponse safeQueueResponse(RuntimeWorkQueueDescriptor descriptor, int sampleLimit) {
        try {
            return queueResponse(descriptor, sampleLimit);
        } catch (RuntimeException ex) {
            log.warn("Failed to load runtime DLQ queue snapshot for taskType={}", descriptor.taskType(), ex);
            return emptyQueueResponse(descriptor);
        }
    }

    private RuntimeWorkDlqQueueResponse queueResponse(RuntimeWorkQueueDescriptor descriptor, int sampleLimit) {
        RuntimeWorkQueueCounts counts = workQueue.counts(descriptor.taskType());
        List<RuntimeWorkDlqItemResponse> samples = sampleLimit <= 0
                ? List.of()
                : workQueue.sampleDlq(descriptor.taskType(), sampleLimit).stream()
                        .map(this::itemResponse)
                        .toList();
        return new RuntimeWorkDlqQueueResponse(
                descriptor.key(),
                descriptor.label(),
                descriptor.taskType(),
                counts.pending(),
                counts.inflight(),
                counts.dlq(),
                workQueue.dlqConsumerCount(descriptor.taskType()),
                samples);
    }

    private RuntimeWorkDlqQueueResponse emptyQueueResponse(RuntimeWorkQueueDescriptor descriptor) {
        return new RuntimeWorkDlqQueueResponse(
                descriptor.key(),
                descriptor.label(),
                descriptor.taskType(),
                0,
                0,
                0,
                0,
                List.of());
    }

    private RuntimeWorkDlqItemResponse itemResponse(RuntimeWorkItem item) {
        return new RuntimeWorkDlqItemResponse(
                item.taskType(),
                item.taskId(),
                item.submissionId(),
                item.aggregateId(),
                item.version(),
                item.status(),
                item.attempt(),
                item.createdAt(),
                item.updatedAt(),
                item.ownerId(),
                item.lastError(),
                preview(item.payload()));
    }

    private RuntimeWorkQueueDescriptor descriptor(String taskType) {
        return RuntimeWorkQueueCatalog.findByTaskType(taskType)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported runtime work taskType: " + taskType));
    }

    private static int safeSampleLimit(int limit) {
        if (limit <= 0) {
            return 0;
        }
        return Math.min(limit, MAX_SAMPLE_LIMIT);
    }

    private static int safeActionLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(limit, MAX_ACTION_LIMIT);
    }

    private Duration cacheTtl() {
        if (runtimeConfig == null) {
            return DEFAULT_CACHE_TTL;
        }
        Duration configured = runtimeConfig.positiveDurationValue(CACHE_TTL_CONFIG_KEY, DEFAULT_CACHE_TTL);
        return configured == null ? DEFAULT_CACHE_TTL : configured;
    }

    private static boolean cacheEnabled(Duration ttl) {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }

    private static String preview(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= PAYLOAD_PREVIEW_LIMIT
                ? trimmed
                : trimmed.substring(0, PAYLOAD_PREVIEW_LIMIT);
    }

    private record CachedQueues(List<RuntimeWorkDlqQueueResponse> responses, Instant expiresAt) {

        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
