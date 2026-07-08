package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class SearchSyncWorkQueueHealthIndicator implements HealthIndicator {

    private final Environment environment;
    private final ObjectProvider<SearchSyncWorkQueueWorker> workerProvider;
    private final RuntimeWorkQueue workQueue;

    @Override
    public Health health() {
        boolean enabled = enabled();
        SearchSyncWorkQueueWorker worker = workerProvider.getIfAvailable();
        if (!enabled) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("workerBean", worker != null)
                    .build();
        }
        if (worker == null) {
            return Health.status(Status.OUT_OF_SERVICE)
                    .withDetail("enabled", true)
                    .withDetail("workerBean", false)
                    .withDetail("reason", "search_work_queue_worker_enabled_but_bean_missing")
                    .build();
        }

        SearchSyncWorkQueueState state = worker.state();
        Health.Builder builder = state.consecutiveFailures() > 0
                ? Health.down().withDetail("reason", "search_work_queue_last_run_failed")
                : Health.up();
        try {
            return builder
                    .withDetail("enabled", true)
                    .withDetail("workerBean", true)
                    .withDetail("workerId", state.workerId())
                    .withDetail("running", state.running())
                    .withDetail("lastRunAt", valueOrNever(state.lastRunAt()))
                    .withDetail("lastSuccessAt", valueOrNever(state.lastSuccessAt()))
                    .withDetail("lastFailureAt", valueOrNever(state.lastFailureAt()))
                    .withDetail("lastProgressAt", valueOrNever(state.lastProgressAt()))
                    .withDetail("lastProcessed", state.lastProcessed())
                    .withDetail("lastFailed", state.lastFailed())
                    .withDetail("lastRequeued", state.lastRequeued())
                    .withDetail("consecutiveFailures", state.consecutiveFailures())
                    .withDetail("lastError", StringUtils.hasText(state.lastError()) ? state.lastError() : "none")
                    .withDetail("lanes", state.lanes())
                    .withDetail("workQueue", workQueueDetails())
                    .build();
        } catch (RuntimeException ex) {
            return Health.down()
                    .withDetail("enabled", true)
                    .withDetail("workerBean", true)
                    .withDetail("reason", "runtime_work_queue_unavailable")
                    .build();
        }
    }

    private Map<String, Object> workQueueDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(SearchSyncWorkTypes.RAW, details(workQueue.counts(SearchSyncWorkTypes.RAW)));
        details.put(SearchSyncWorkTypes.UNIFIED, details(workQueue.counts(SearchSyncWorkTypes.UNIFIED)));
        return details;
    }

    private Map<String, Object> details(RuntimeWorkQueueCounts counts) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pending", counts.pending());
        details.put("inflight", counts.inflight());
        details.put("dlq", counts.dlq());
        return details;
    }

    private Object valueOrNever(Instant timestamp) {
        return timestamp == null ? "never" : timestamp;
    }

    private boolean enabled() {
        String raw = firstNonBlank(
                environment.getProperty("app.search.work-queue.worker.enabled"),
                environment.getProperty("APP_SEARCH_WORK_QUEUE_WORKER_ENABLED"));
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            default -> false;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
