package com.prodigalgal.ircs.ops.dashboard.application;

import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.dashboard.dto.SystemMetricsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Slf4j
public class DashboardStreamService {

    static final String TOPIC_METRICS = "metrics";
    static final String TOPIC_TASK_RUNTIME = "task-runtime";
    static final String TOPIC_SEARCH_OPS = "search-ops";
    static final String TOPIC_AGGREGATION_OPS = "aggregation-ops";

    private final DashboardQueryService dashboardQueryService;
    private final ExecutorService streamExecutor;
    private final RuntimeConfigService runtimeConfig;
    private final Duration fallbackInterval;
    private final Duration fallbackTimeout;

    DashboardStreamService(
            DashboardQueryService dashboardQueryService,
            RuntimeConfigService runtimeConfig) {
        this.dashboardQueryService = dashboardQueryService;
        this.streamExecutor = VirtualThreadExecutors.newPerTaskExecutor("dashboard-topic-stream-");
        this.runtimeConfig = runtimeConfig;
        this.fallbackInterval = Duration.ofSeconds(3);
        this.fallbackTimeout = Duration.ofHours(1);
    }

    public SseEmitter stream(String topic, int taskRuntimeLimit) {
        String safeTopic = normalizeTopic(topic);
        SseEmitter emitter = new SseEmitter(timeout().toMillis());
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(error -> open.set(false));
        streamExecutor.execute(() -> pump(emitter, open, safeTopic, taskRuntimeLimit));
        return emitter;
    }

    Object topicPayloadForTests(String topic, int taskRuntimeLimit) {
        return topicPayload(normalizeTopic(topic), taskRuntimeLimit);
    }

    Duration intervalForTests(String topic) {
        return interval(normalizeTopic(topic));
    }

    private void pump(SseEmitter emitter, AtomicBoolean open, String topic, int taskRuntimeLimit) {
        try {
            while (open.get()) {
                sendTopic(emitter, topic, taskRuntimeLimit);
                sleep(interval(topic));
            }
        } catch (IOException ex) {
            log.debug("Dashboard {} stream client disconnected: {}", topic, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Dashboard {} stream failed: {}", topic, ex.getMessage());
            emitter.completeWithError(ex);
            return;
        }
        emitter.complete();
    }

    private void sendTopic(SseEmitter emitter, String topic, int taskRuntimeLimit) throws IOException {
        emitter.send(SseEmitter.event()
                .name(topic)
                .data(topicPayload(topic, taskRuntimeLimit)));
    }

    private Object topicPayload(String topic, int taskRuntimeLimit) {
        return switch (topic) {
            case TOPIC_METRICS -> safeMetrics();
            case TOPIC_TASK_RUNTIME -> safeTaskRuntime(taskRuntimeLimit);
            case TOPIC_SEARCH_OPS -> dashboardQueryService.getSearchOpsStats();
            case TOPIC_AGGREGATION_OPS -> dashboardQueryService.getAggregationOpsStats();
            default -> throw new IllegalArgumentException("Unsupported dashboard stream topic: " + topic);
        };
    }

    private SystemMetricsResponse safeMetrics() {
        try {
            return dashboardQueryService.getMetrics();
        } catch (RuntimeException ex) {
            log.debug("Dashboard metrics topic fallback: {}", ex.getMessage());
            return DashboardFallbacks.metrics("METRICS_UNAVAILABLE");
        }
    }

    private TaskRuntimeOverviewResponse safeTaskRuntime(int taskRuntimeLimit) {
        int safeLimit = Math.max(1, taskRuntimeLimit);
        try {
            return dashboardQueryService.getTaskRuntimeOverview(safeLimit);
        } catch (RuntimeException ex) {
            log.debug("Dashboard task-runtime topic fallback: {}", ex.getMessage());
            return DashboardFallbacks.taskRuntime(safeLimit);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dashboard stream interrupted", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
    }

    private Duration interval(String topic) {
        String key = "app.ops.dashboard.stream." + topic + ".interval";
        return runtimeConfig == null
                ? fallbackInterval
                : runtimeConfig.positiveDurationValue(key, commonInterval());
    }

    private Duration commonInterval() {
        return runtimeConfig == null
                ? fallbackInterval
                : runtimeConfig.positiveDurationValue("app.ops.dashboard.stream.interval", fallbackInterval);
    }

    private Duration timeout() {
        return runtimeConfig == null
                ? fallbackTimeout
                : runtimeConfig.positiveDurationValue("app.ops.dashboard.stream.timeout", fallbackTimeout);
    }

    private static String normalizeTopic(String topic) {
        String normalized = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        if (Map.of(
                TOPIC_METRICS, true,
                TOPIC_TASK_RUNTIME, true,
                TOPIC_SEARCH_OPS, true,
                TOPIC_AGGREGATION_OPS, true).containsKey(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported dashboard stream topic: " + topic);
    }
}
