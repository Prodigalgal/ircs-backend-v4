package com.prodigalgal.ircs.ops.dashboard.application;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.work.SystemConfigWorkSubmissionGate;
import com.prodigalgal.ircs.ops.queue.domain.QueueConsumerPolicy;
import com.prodigalgal.ircs.ops.queue.domain.RuntimeWorkMetricRole;
import com.prodigalgal.ircs.ops.dashboard.dto.RateMetricResponse;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueBlockedReasonResolver {

    private final RuntimeConfigService runtimeConfig;

    String runtimeWorkBlockedReason(
            String taskType,
            RuntimeWorkMetricRole role,
            long messageCount,
            int consumerCount,
            RateMetricResponse rate,
            Map<String, Object> aggregationOpsStats) {
        String processingReason = aggregationProcessingReason(
                messageCount,
                consumerCount,
                rate,
                taskType,
                role,
                aggregationOpsStats);
        return processingReason == null
                ? blockedReason(messageCount, consumerCount, runtimeWorkPolicy(taskType), rate)
                : processingReason;
    }

    String blockedReason(long messageCount, int consumerCount, QueueConsumerPolicy policy) {
        return blockedReason(messageCount, consumerCount, policy, null);
    }

    String blockedReason(
            long messageCount,
            int consumerCount,
            QueueConsumerPolicy policy,
            RateMetricResponse rate) {
        if (messageCount <= 0L || consumerCount > 0) {
            return null;
        }
        if (recentlyActive(rate)) {
            return null;
        }
        if (policy == null || !policy.hasConsumer()) {
            return "NO_CONSUMER_IMPL";
        }
        for (SystemConfigWorkSubmissionGate.ConfigFlag flag : policy.enabledFlags()) {
            if (!runtimeConfig.booleanValue(flag.key(), flag.defaultEnabled())) {
                return "DISABLED_BY_CONFIG";
            }
        }
        return "NO_ACTIVE_CONSUMER";
    }

    boolean recentlyActive(RateMetricResponse rate) {
        return rate != null
                && (!rate.stale()
                || rate.instantCount() > 0L
                || rate.stableCount() > 0L
                || rate.instantTpm() > 0L
                || rate.stableTpm() > 0L);
    }

    private String aggregationProcessingReason(
            long messageCount,
            int consumerCount,
            RateMetricResponse rate,
            String taskType,
            RuntimeWorkMetricRole role,
            Map<String, Object> aggregationOpsStats) {
        if (!AggregationWorkTypes.RAW_VIDEO.equals(taskType)
                || (role != RuntimeWorkMetricRole.PENDING && role != RuntimeWorkMetricRole.INFLIGHT)
                || messageCount <= 0L
                || consumerCount <= 0
                || recentlyActive(rate)) {
            return null;
        }
        Map<String, Object> worker = mapValue(aggregationOpsStats, "worker");
        if (!boolValue(worker.get("running"))) {
            return null;
        }
        String currentStage = stringValue(worker.get("currentStage"));
        if (currentStage == null || currentStage.isBlank()) {
            return null;
        }
        long runningForSeconds = longValue(worker.get("runningForSeconds"));
        long thresholdSeconds = Math.max(1L, aggregationProcessingStallThreshold().toSeconds());
        return runningForSeconds >= thresholdSeconds ? "CONSUMER_PROCESSING_STUCK" : "PROCESSING";
    }

    private QueueConsumerPolicy runtimeWorkPolicy(String taskType) {
        return SystemConfigWorkSubmissionGate.runtimeConsumerFlags(taskType)
                .map(QueueConsumerPolicy::enabled)
                .orElseGet(QueueConsumerPolicy::missing);
    }

    private Duration aggregationProcessingStallThreshold() {
        return runtimeConfig.positiveDurationValue(
                "app.aggregation.work-queue.worker.processing-stall-threshold",
                Duration.ofMinutes(10));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> source, String key) {
        if (source == null) {
            return Map.of();
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
