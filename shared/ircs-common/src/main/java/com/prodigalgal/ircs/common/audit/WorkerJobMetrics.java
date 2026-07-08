package com.prodigalgal.ircs.common.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.util.StringUtils;

final class WorkerJobMetrics {

    private static final int TAG_LIMIT = 128;
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;
    private final String workerId;

    WorkerJobMetrics(MeterRegistry registry, String workerId) {
        this.registry = registry;
        this.workerId = tagValue(workerId);
    }

    static WorkerJobMetrics noop() {
        return new WorkerJobMetrics(null, UNKNOWN);
    }

    void record(WorkerJobAuditEvent event) {
        if (registry == null || event == null) {
            return;
        }
        String jobType = tagValue(event.jobType());
        String jobName = tagValue(event.jobName());
        String outcome = tagValue(event.status());
        Duration duration = nonNegative(event.duration());
        Tags tags = Tags.of(
                "worker_id", workerId,
                "job_type", jobType,
                "job_name", jobName,
                "outcome", outcome);

        Counter.builder("ircs.worker.job.runs")
                .description("Worker, queue consumer and maintenance task executions by outcome")
                .tags(tags)
                .register(registry)
                .increment();
        Timer.builder("ircs.worker.job.duration")
                .description("Worker, queue consumer and maintenance task execution duration")
                .tags(tags)
                .register(registry)
                .record(duration);
    }

    private static Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private static String tagValue(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        String normalized = value.trim();
        if (normalized.length() <= TAG_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, TAG_LIMIT);
    }
}
