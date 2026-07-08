package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueueClient;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueueSnapshot;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueues;
import com.prodigalgal.ircs.ops.queue.domain.QueueState;
import com.prodigalgal.ircs.ops.dashboard.dto.RedisMetricResponse;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SystemMetricsDataReader {

    private final RabbitAdmin rabbitAdmin;
    private final RedisConnectionFactory redisConnectionFactory;
    private final MeterRegistry meterRegistry;
    private final ObjectProvider<RuntimeWorkQueue> runtimeWorkQueueProvider;
    private final ObjectProvider<AggregationOpsStatsClient> aggregationOpsStatsClientProvider;
    private final RabbitManagementQueueClient rabbitManagementQueueClient;
    private volatile CachedRabbitQueues cachedRabbitQueues;

    public void registerRabbitQueueGauges() {
        for (QueueTopic topic : QueueTopic.values()) {
            registerQueueGauges(topic, topic.queueName(), "main", false);
            registerQueueGauges(topic, topic.retryName(), "retry", false);
            registerQueueGauges(topic, topic.dlqName(), "dlq", true);
        }
    }

    public void registerRuntimeWorkGauges(String taskType) {
        Gauge.builder("ircs.valkey.runtime_work.pending", () -> runtimeWorkCounts(taskType).pending())
                .description("Valkey runtime work pending task count observed by ops-service")
                .tag("task_type", taskType)
                .register(meterRegistry);
        Gauge.builder("ircs.valkey.runtime_work.inflight", () -> runtimeWorkCounts(taskType).inflight())
                .description("Valkey runtime work inflight task count observed by ops-service")
                .tag("task_type", taskType)
                .register(meterRegistry);
        Gauge.builder("ircs.valkey.runtime_work.dlq", () -> runtimeWorkCounts(taskType).dlq())
                .description("Valkey runtime work DLQ task count observed by ops-service")
                .tag("task_type", taskType)
                .register(meterRegistry);
    }

    public QueueState queueState(String queueName) {
        RabbitManagementQueueSnapshot snapshot = nativeQueueSnapshot(queueName);
        if (snapshot != null) {
            return new QueueState(snapshot.messagesTotal(), snapshot.consumers());
        }
        try {
            Properties props = rabbitAdmin.getQueueProperties(queueName);
            if (props == null) {
                return QueueState.empty();
            }
            Object messages = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            Object consumers = props.get(RabbitAdmin.QUEUE_CONSUMER_COUNT);
            return new QueueState(toInt(messages), toInt(consumers));
        } catch (Exception ignored) {
            return QueueState.empty();
        }
    }

    private RabbitManagementQueueSnapshot nativeQueueSnapshot(String queueName) {
        Map<String, RabbitManagementQueueSnapshot> snapshots = nativeQueueSnapshots();
        return snapshots.get(queueName);
    }

    private Map<String, RabbitManagementQueueSnapshot> nativeQueueSnapshots() {
        long now = System.currentTimeMillis();
        CachedRabbitQueues cached = cachedRabbitQueues;
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.snapshots();
        }
        Optional<RabbitManagementQueues> queues = rabbitManagementQueueClient.fetchQueueSnapshots();
        Map<String, RabbitManagementQueueSnapshot> snapshots = queues
                .map(RabbitManagementQueues::byName)
                .orElse(Collections.emptyMap());
        cachedRabbitQueues = new CachedRabbitQueues(snapshots, now + Duration.ofSeconds(1).toMillis());
        return snapshots;
    }

    public RedisMetricResponse redisMetric() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Properties info = connection.serverCommands().info();
            if (info == null) {
                return null;
            }
            return new RedisMetricResponse(
                    parseLong(info.getProperty("used_memory")),
                    (int) parseLong(info.getProperty("connected_clients")),
                    parseLong(info.getProperty("instantaneous_ops_per_sec")),
                    info.getProperty("redis_version"));
        } catch (Exception ignored) {
            return null;
        }
    }

    public RuntimeWorkQueueCounts runtimeWorkCounts(String taskType) {
        RuntimeWorkQueue queue = runtimeWorkQueueProvider.getIfAvailable();
        if (queue == null) {
            return emptyRuntimeWorkCounts();
        }
        try {
            RuntimeWorkQueueCounts counts = queue.counts(taskType);
            return counts == null ? emptyRuntimeWorkCounts() : counts;
        } catch (Exception ignored) {
            return emptyRuntimeWorkCounts();
        }
    }

    public long runtimeWorkConsumerCount(String taskType) {
        RuntimeWorkQueue queue = runtimeWorkQueueProvider.getIfAvailable();
        if (queue == null) {
            return 0L;
        }
        try {
            return queue.consumerCount(taskType);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public long runtimeWorkDlqConsumerCount(String taskType) {
        RuntimeWorkQueue queue = runtimeWorkQueueProvider.getIfAvailable();
        if (queue == null) {
            return 0L;
        }
        try {
            return queue.dlqConsumerCount(taskType);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public Map<String, Object> aggregationOpsStats() {
        AggregationOpsStatsClient client = aggregationOpsStatsClientProvider == null
                ? null
                : aggregationOpsStatsClientProvider.getIfAvailable();
        if (client == null) {
            return Map.of();
        }
        try {
            Map<String, Object> stats = client.currentStats();
            return stats == null ? Map.of() : stats;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private void registerQueueGauges(QueueTopic topic, String queueName, String queueRole, boolean dlq) {
        Gauge.builder("ircs.rabbit.queue.messages", () -> queueState(queueName).messageCount())
                .description("RabbitMQ queue message depth observed by ops-service")
                .tag("topic", topic.name())
                .tag("queue", queueName)
                .tag("queue_role", queueRole)
                .tag("dlq", String.valueOf(dlq))
                .register(meterRegistry);
        Gauge.builder("ircs.rabbit.queue.consumers", () -> queueState(queueName).consumerCount())
                .description("RabbitMQ queue consumer count observed by ops-service")
                .tag("topic", topic.name())
                .tag("queue", queueName)
                .tag("queue_role", queueRole)
                .tag("dlq", String.valueOf(dlq))
                .register(meterRegistry);
    }

    private RuntimeWorkQueueCounts emptyRuntimeWorkCounts() {
        return new RuntimeWorkQueueCounts(0, 0, 0);
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private record CachedRabbitQueues(
            Map<String, RabbitManagementQueueSnapshot> snapshots,
            long expiresAtMillis
    ) {
    }
}
