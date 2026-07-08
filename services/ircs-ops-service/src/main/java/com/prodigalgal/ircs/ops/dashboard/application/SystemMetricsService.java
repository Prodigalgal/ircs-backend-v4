package com.prodigalgal.ircs.ops.dashboard.application;

import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.metrics.RateMetricKeys;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkQueueCatalog;
import com.prodigalgal.ircs.ops.queue.domain.QueueConsumerPolicy;
import com.prodigalgal.ircs.ops.queue.domain.QueueState;
import com.prodigalgal.ircs.ops.queue.domain.QueueTopicDescriptor;
import com.prodigalgal.ircs.ops.dashboard.domain.RateMetricSnapshot;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkMetricRole;
import com.prodigalgal.ircs.ops.dashboard.dto.QueueGroupResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.QueueMetricResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.RateKind;
import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.RedisMetricResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.SystemMetricsResponse;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.RabbitManagementRateProbe;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.SystemMetricsDataReader;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemMetricsService {

    private final ObjectProvider<RabbitManagementRateProbe> rabbitRateProbeProvider;
    private final RuntimeConfigService runtimeConfig;
    private final SystemMetricsDataReader dataReader;
    private final RateMetricSnapshotReader rateMetricSnapshotReader;
    private final QueueBlockedReasonResolver blockedReasonResolver;

    private final ExecutorService metricsRefreshExecutor =
            VirtualThreadExecutors.newPerTaskExecutor("ops-metrics-refresh-");
    private final AtomicBoolean metricsRefreshRunning = new AtomicBoolean(false);
    private volatile CachedMetrics cachedMetrics;

    @PostConstruct
    void registerRabbitQueueGauges() {
        dataReader.registerRabbitQueueGauges();
        RuntimeWorkQueueCatalog.descriptors()
                .forEach(descriptor -> dataReader.registerRuntimeWorkGauges(descriptor.taskType()));
    }

    public SystemMetricsResponse currentMetrics() {
        Duration cacheTtl = metricsCacheTtl();
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return loadCurrentMetrics();
        }
        long now = System.currentTimeMillis();
        CachedMetrics cached = cachedMetrics;
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.response();
        }
        refreshMetricsAsync(cacheTtl);
        return cachedOrFallback(cached, "METRICS_REFRESHING");
    }

    private SystemMetricsResponse loadCurrentMetrics() {
        Runtime runtime = Runtime.getRuntime();
        File disk = new File(".");
        RateMetricSnapshot rateSnapshot = rateMetricSnapshotReader.currentSnapshot();
        Map<String, Object> aggregationOpsStats = dataReader.aggregationOpsStats();
        MetricsReadContext context = new MetricsReadContext(rateSnapshot, aggregationOpsStats);
        List<QueueGroupResponse> queueGroups = queueGroups(context);
        QueueMetricResponse ingest = findQueue(queueGroups, QueueTopic.INGEST_VIDEO.name());
        QueueMetricResponse image = findQueue(queueGroups, QueueTopic.DOWNLOAD_IMAGE.name());
        RuntimeWorkQueueCounts normalize = context.runtimeWorkCounts(PipelineRuntimeWorkTypes.NORMALIZE_VIDEO);
        RuntimeWorkQueueCounts enrich = context.runtimeWorkCounts(PipelineRuntimeWorkTypes.ENRICH_METADATA);

        return new SystemMetricsResponse(
                rateSnapshot.metric(RateMetricKeys.COLLECTION_PAGE_DISCOVERED, "PT 发现速率"),
                rateSnapshot.metric(RateMetricKeys.COLLECTION_DETAIL_COMPLETED, "DT 采集速率"),
                rateSnapshot.metric(RateMetricKeys.runtimeMetric(
                        PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                        RateMetricKeys.ACTION_COMPLETED), "清洗速率"),
                rateSnapshot.metric("metadata.combined", "元数据速率",
                        RateMetricKeys.runtimeMetric(PipelineRuntimeWorkTypes.ENRICH_METADATA, RateMetricKeys.ACTION_COMPLETED),
                        RateMetricKeys.runtimeMetric(PipelineRuntimeWorkTypes.METADATA_PROVIDER, RateMetricKeys.ACTION_COMPLETED)),
                messageCount(ingest),
                activeBacklog(normalize),
                activeBacklog(enrich),
                messageCount(image),
                queueGroups,
                queueGroups.stream()
                        .filter(group -> !group.dlq())
                        .flatMap(group -> group.queues().stream())
                        .mapToLong(QueueMetricResponse::messageCount)
                        .sum(),
                dataReader.redisMetric(),
                runtime.freeMemory(),
                disk.getFreeSpace(),
                r2Healthy(context),
                Thread.activeCount(),
                Map.of("data", List.of()));
    }

    public void refreshRateMetrics() {
        cachedMetrics = null;
        refreshMetricsAsync(metricsCacheTtl());
    }

    @PreDestroy
    void shutdownMetricsRefreshExecutor() {
        metricsRefreshExecutor.shutdownNow();
    }

    private void refreshMetricsAsync(Duration cacheTtl) {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return;
        }
        if (!metricsRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            metricsRefreshExecutor.execute(() -> refreshMetricsCache(cacheTtl));
        } catch (RuntimeException ex) {
            metricsRefreshRunning.set(false);
            log.debug("System metrics async refresh scheduling failed: {}", ex.getMessage());
        }
    }

    private void refreshMetricsCache(Duration cacheTtl) {
        try {
            SystemMetricsResponse response = loadCurrentMetrics();
            cachedMetrics = new CachedMetrics(
                    response,
                    System.currentTimeMillis() + cacheTtl.toMillis());
        } catch (RuntimeException ex) {
            log.debug("System metrics async refresh failed: {}", ex.getMessage());
        } finally {
            metricsRefreshRunning.set(false);
        }
    }

    private SystemMetricsResponse cachedOrFallback(CachedMetrics cached, String fallbackReason) {
        return cached == null || cached.response() == null
                ? DashboardFallbacks.metrics(fallbackReason)
                : cached.response();
    }

    private List<QueueGroupResponse> queueGroups(MetricsReadContext context) {
        Map<String, List<QueueMetricResponse>> normalGroups = new LinkedHashMap<>();
        List<QueueMetricResponse> dlqs = new ArrayList<>();

        for (QueueTopic topic : QueueTopic.values()) {
            QueueTopicDescriptor descriptor = QueueTopicDescriptor.from(topic);
            QueueMetricResponse normal = queueMetric(
                    descriptor.displayName(),
                    topic.name(),
                    descriptor.color(),
                    topic,
                    RabbitManagementRateProbe.RabbitQueueRole.MAIN,
                    topic.queueName(),
                    true,
                    context);
            normalGroups.computeIfAbsent(descriptor.group(), ignored -> new ArrayList<>()).add(normal);

            dlqs.add(queueMetric(
                    descriptor.displayName() + " DLQ",
                    "DLQ_" + topic.name(),
                    "#dc2626",
                    topic,
                    RabbitManagementRateProbe.RabbitQueueRole.DLQ,
                    topic.dlqName(),
                    false,
                    context));
        }
        List<QueueMetricResponse> runtimeWorkQueues = valkeyRuntimeWorkQueues(context);
        if (!runtimeWorkQueues.isEmpty()) {
            normalGroups.put("Valkey Runtime Work", runtimeWorkQueues);
        }

        List<QueueGroupResponse> groups = new ArrayList<>();
        normalGroups.forEach((title, queues) -> groups.add(new QueueGroupResponse(title, false, queues)));
        List<QueueMetricResponse> valkeyDlqs = valkeyRuntimeWorkDlqs(context);
        if (!valkeyDlqs.isEmpty()) {
            dlqs.addAll(valkeyDlqs);
        }
        groups.add(new QueueGroupResponse("死信队列", true, dlqs));
        return groups;
    }

    private List<QueueMetricResponse> valkeyRuntimeWorkQueues(MetricsReadContext context) {
        List<QueueMetricResponse> queues = new ArrayList<>();
        RuntimeWorkQueueCatalog.descriptors().forEach(descriptor -> {
            addRuntimeWorkQueueMetrics(queues, descriptor.label() + " Pending",
                    "RUNTIME_" + descriptor.key() + "_PENDING", descriptor.color(), descriptor.taskType(),
                    RuntimeWorkMetricRole.PENDING, context);
            addRuntimeWorkQueueMetrics(queues, descriptor.label() + " Inflight",
                    "RUNTIME_" + descriptor.key() + "_INFLIGHT", descriptor.inflightColor(), descriptor.taskType(),
                    RuntimeWorkMetricRole.INFLIGHT, context);
        });
        return queues;
    }

    private List<QueueMetricResponse> valkeyRuntimeWorkDlqs(MetricsReadContext context) {
        List<QueueMetricResponse> dlqs = new ArrayList<>();
        RuntimeWorkQueueCatalog.descriptors().forEach(descriptor -> addRuntimeWorkQueueMetrics(dlqs, descriptor.label() + " Runtime DLQ",
                "RUNTIME_" + descriptor.key() + "_DLQ", "#dc2626", descriptor.taskType(),
                RuntimeWorkMetricRole.DLQ, context));
        return dlqs;
    }

    private void addRuntimeWorkQueueMetrics(
            List<QueueMetricResponse> target,
            String name,
            String key,
            String color,
            String taskType,
            RuntimeWorkMetricRole role,
            MetricsReadContext context) {
        RuntimeWorkQueueCounts counts = context.runtimeWorkCounts(taskType);
        long current = switch (role) {
            case PENDING -> counts.pending();
            case INFLIGHT -> counts.inflight();
            case DLQ -> counts.dlq();
        };
        RateDescriptor rateDescriptor = runtimeRate(taskType, role);
        RateMetricResponse rate = context.metric(rateDescriptor.metricKey(), rateDescriptor.label());
        int consumers = role == RuntimeWorkMetricRole.DLQ
                ? safeInt(context.runtimeWorkDlqConsumerCount(taskType))
                : safeInt(context.runtimeWorkConsumerCount(taskType));
        target.add(new QueueMetricResponse(
                name,
                key,
                color,
                safeInt(current),
                consumers,
                role == RuntimeWorkMetricRole.DLQ
                        ? null
                        : blockedReasonResolver.runtimeWorkBlockedReason(
                                taskType,
                                role,
                                current,
                                consumers,
                                rate,
                                context.aggregationOpsStats()),
                rateDescriptor.kind(),
                rate));
    }

    private QueueMetricResponse queueMetric(
            String name,
            String key,
            String color,
            QueueTopic topic,
            RabbitManagementRateProbe.RabbitQueueRole role,
            String queueName,
            boolean checkConsumers,
            MetricsReadContext context) {
        QueueState state = context.queueState(queueName);
        RabbitRateDescriptor rateDescriptor = rabbitRate(topic, role, context.rateSnapshot());
        return new QueueMetricResponse(
                name,
                key,
                color,
                state.messageCount(),
                state.consumerCount(),
                checkConsumers
                        ? blockedReasonResolver.blockedReason(
                                state.messageCount(),
                                state.consumerCount(),
                                QueueConsumerPolicy.enabled())
                        : null,
                rateDescriptor.kind(),
                rateDescriptor.rate());
    }

    private Duration metricsCacheTtl() {
        return runtimeConfig.durationValue("app.ops.metrics.cache-ttl", Duration.ofSeconds(15));
    }

    private QueueMetricResponse findQueue(List<QueueGroupResponse> groups, String key) {
        for (QueueGroupResponse group : groups) {
            for (QueueMetricResponse queue : group.queues()) {
                if (queue.key().equals(key)) {
                    return queue;
                }
            }
        }
        return null;
    }

    private int messageCount(QueueMetricResponse queue) {
        return queue == null ? 0 : queue.messageCount();
    }

    private boolean r2Healthy(MetricsReadContext context) {
        RuntimeWorkQueueCounts avatar = context.runtimeWorkCounts(StorageWorkTypes.AVATAR_SYNC);
        RuntimeWorkQueueCounts cover = context.runtimeWorkCounts(StorageWorkTypes.COVER_R2_SYNC);
        return avatar.dlq() == 0
                && cover.dlq() == 0
                && (activeRuntimeBacklog(avatar) == 0
                        || context.runtimeWorkConsumerCount(StorageWorkTypes.AVATAR_SYNC) > 0)
                && (activeRuntimeBacklog(cover) == 0
                        || context.runtimeWorkConsumerCount(StorageWorkTypes.COVER_R2_SYNC) > 0);
    }

    private long activeRuntimeBacklog(RuntimeWorkQueueCounts counts) {
        return counts.pending() + counts.inflight();
    }

    private RateDescriptor runtimeRate(String taskType, RuntimeWorkMetricRole role) {
        return switch (role) {
            case PENDING -> new RateDescriptor(
                    RateKind.CLAIM,
                    RateMetricKeys.runtimeMetric(taskType, RateMetricKeys.ACTION_CLAIMED),
                    "领取速率");
            case INFLIGHT -> new RateDescriptor(
                    RateKind.COMPLETE,
                    RateMetricKeys.runtimeMetric(taskType, RateMetricKeys.ACTION_COMPLETED),
                    "完成速率");
            case DLQ -> new RateDescriptor(
                    RateKind.FAIL,
                    RateMetricKeys.runtimeMetric(taskType, RateMetricKeys.ACTION_FAILED),
                    "失败速率");
        };
    }

    private RabbitRateDescriptor rabbitRate(
            QueueTopic topic,
            RabbitManagementRateProbe.RabbitQueueRole role,
            RateMetricSnapshot rateSnapshot) {
        RabbitManagementRateProbe probe = rabbitRateProbeProvider == null
                ? null
                : rabbitRateProbeProvider.getIfAvailable();
        boolean managementAvailable = probe != null && probe.rateAvailable();
        if (role == RabbitManagementRateProbe.RabbitQueueRole.DLQ) {
            RateMetricResponse published = rateSnapshot.metric(
                    RabbitManagementRateProbe.metricKey(
                            topic,
                            role,
                            RabbitManagementRateProbe.RabbitRateAction.PUBLISHED),
                    "入死信速率");
            return managementAvailable || blockedReasonResolver.recentlyActive(published)
                    ? new RabbitRateDescriptor(RateKind.PUBLISH, published)
                    : new RabbitRateDescriptor(RateKind.NONE, rateSnapshot.empty(topic.name(), "Rabbit 速率不可用"));
        }

        RateMetricResponse acked = rateSnapshot.metric(
                RabbitManagementRateProbe.metricKey(
                        topic,
                        role,
                        RabbitManagementRateProbe.RabbitRateAction.ACKED),
                "确认速率");
        RateMetricResponse delivered = rateSnapshot.metric(
                RabbitManagementRateProbe.metricKey(
                        topic,
                        role,
                        RabbitManagementRateProbe.RabbitRateAction.DELIVERED),
                "投递速率");
        if (blockedReasonResolver.recentlyActive(delivered) && !blockedReasonResolver.recentlyActive(acked)) {
            return new RabbitRateDescriptor(RateKind.DELIVER, delivered);
        }
        return managementAvailable || blockedReasonResolver.recentlyActive(acked)
                ? new RabbitRateDescriptor(RateKind.ACK, acked)
                : new RabbitRateDescriptor(RateKind.NONE, rateSnapshot.empty(topic.name(), "Rabbit 速率不可用"));
    }

    private int safeInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private int activeBacklog(RuntimeWorkQueueCounts counts) {
        if (counts == null) {
            return 0;
        }
        return safeInt(counts.pending() + counts.inflight());
    }

    private record CachedMetrics(SystemMetricsResponse response, long expiresAtMillis) {
    }

    private record RateDescriptor(RateKind kind, String metricKey, String label) {
    }

    private record RabbitRateDescriptor(RateKind kind, RateMetricResponse rate) {
    }

    private final class MetricsReadContext {

        private final RateMetricSnapshot rateSnapshot;
        private final Map<String, Object> aggregationOpsStats;
        private final Map<String, QueueState> queueStates = new HashMap<>();
        private final Map<String, RuntimeWorkQueueCounts> runtimeWorkCounts = new HashMap<>();
        private final Map<String, Long> runtimeWorkConsumers = new HashMap<>();
        private final Map<String, Long> runtimeWorkDlqConsumers = new HashMap<>();

        private MetricsReadContext(
                RateMetricSnapshot rateSnapshot,
                Map<String, Object> aggregationOpsStats) {
            this.rateSnapshot = rateSnapshot;
            this.aggregationOpsStats = aggregationOpsStats == null ? Map.of() : aggregationOpsStats;
        }

        private RateMetricSnapshot rateSnapshot() {
            return rateSnapshot;
        }

        private Map<String, Object> aggregationOpsStats() {
            return aggregationOpsStats;
        }

        private RateMetricResponse metric(String metricKey, String label, String... sourceMetricKeys) {
            return rateSnapshot.metric(metricKey, label, sourceMetricKeys);
        }

        private QueueState queueState(String queueName) {
            return queueStates.computeIfAbsent(queueName, dataReader::queueState);
        }

        private RuntimeWorkQueueCounts runtimeWorkCounts(String taskType) {
            return runtimeWorkCounts.computeIfAbsent(taskType, dataReader::runtimeWorkCounts);
        }

        private long runtimeWorkConsumerCount(String taskType) {
            return runtimeWorkConsumers.computeIfAbsent(taskType, dataReader::runtimeWorkConsumerCount);
        }

        private long runtimeWorkDlqConsumerCount(String taskType) {
            return runtimeWorkDlqConsumers.computeIfAbsent(taskType, dataReader::runtimeWorkDlqConsumerCount);
        }
    }

}
