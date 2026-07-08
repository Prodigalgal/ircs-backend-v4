package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class SearchSyncWorkQueueMetrics {

    private final MeterRegistry registry;

    SearchSyncWorkQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordRun(String workerId, Duration duration, String outcome, int processed, int failed, int requeued) {
        String safeWorkerId = safe(workerId);
        String safeOutcome = safe(outcome);
        Counter.builder("ircs.search.work.queue.runs")
                .tag("worker_id", safeWorkerId)
                .tag("outcome", safeOutcome)
                .register(registry)
                .increment();
        Timer.builder("ircs.search.work.queue.run.duration")
                .tag("worker_id", safeWorkerId)
                .tag("outcome", safeOutcome)
                .register(registry)
                .record(duration == null ? Duration.ZERO : duration);
        if (processed > 0) {
            Counter.builder("ircs.search.work.queue.processed")
                    .tag("worker_id", safeWorkerId)
                    .register(registry)
                    .increment(processed);
        }
        if (failed > 0) {
            Counter.builder("ircs.search.work.queue.failed")
                    .tag("worker_id", safeWorkerId)
                    .register(registry)
                    .increment(failed);
        }
        if (requeued > 0) {
            Counter.builder("ircs.search.work.queue.requeued")
                    .tag("worker_id", safeWorkerId)
                    .register(registry)
                    .increment(requeued);
        }
    }

    void recordItem(String workerId, RuntimeWorkItem item, String result, Instant observedAt) {
        Counter.builder("ircs.search.work.queue.items")
                .tag("worker_id", safe(workerId))
                .tag("task_type", item == null ? "unknown" : safe(item.taskType()))
                .tag("version", item == null ? "unknown" : safe(item.version()))
                .tag("result", safe(result))
                .register(registry)
                .increment();
        if (item != null && item.createdAt() != null && observedAt != null) {
            Timer.builder("ircs.search.work.queue.item.delay")
                    .tag("worker_id", safe(workerId))
                    .tag("task_type", safe(item.taskType()))
                    .tag("version", safe(item.version()))
                    .tag("result", safe(result))
                    .register(registry)
                    .record(Duration.between(item.createdAt(), observedAt));
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
