package com.prodigalgal.ircs.task.application;





import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.task.infrastructure.DataSourceSeedCandidate;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.infrastructure.TaskDistributedLockRunner;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleRequest;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduledTask;
import com.prodigalgal.ircs.task.domain.CollectionTaskDefaults;
import com.prodigalgal.ircs.task.dto.TaskCreateRequest;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendDiscoveryTaskService {

    static final String TASK_NAME = "trend-discovery";
    static final String DISCOVERY_PREFIX = "趋势发现";
    static final int DEFAULT_START_PAGE = 1;
    static final int DEFAULT_END_PAGE = 1;
    static final int DEFAULT_FIXED_DELAY_MS = 0;
    static final int DEFAULT_TIMEOUT_MS = 20_000;
    static final int DEFAULT_MAX_RETRIES = 3;

    private final JdbcCollectionTaskRepository taskRepository;
    private final TaskCommandService taskCommandService;
    private final TaskDistributedLockRunner lockRunner;
    private final ObjectMapper objectMapper;

    @Value("${app.task.trend-discovery.enabled:true}")
    private boolean enabled;

    @Value("${app.task.trend-discovery.max-keywords:50}")
    private int maxKeywords;

    @Value("${app.task.trend-discovery.max-data-sources:0}")
    private int maxDataSources;

    public TrendDiscoveryScheduleResponse schedule(TrendDiscoveryScheduleRequest request, String correlationId) {
        return schedule(request, correlationId, true, null);
    }

    public TrendDiscoveryScheduleResponse prepare(
            TrendDiscoveryScheduleRequest request,
            String correlationId,
            Map<String, Object> taskMetadata) {
        return schedule(request, correlationId, false, taskMetadata);
    }

    private TrendDiscoveryScheduleResponse schedule(
            TrendDiscoveryScheduleRequest request,
            String correlationId,
            boolean autoStart,
            Map<String, Object> taskMetadata) {
        List<String> keywords = sanitizeKeywords(request == null ? null : request.keywords());
        if (!enabled) {
            return new TrendDiscoveryScheduleResponse(TASK_NAME, keywords.size(), 0, 0, 0, 0, List.of(),
                    List.of("trend discovery is disabled"));
        }
        if (keywords.isEmpty()) {
            return TrendDiscoveryScheduleResponse.empty(TASK_NAME);
        }
        AtomicReference<TrendDiscoveryScheduleResponse> response = new AtomicReference<>();
        boolean acquired = lockRunner.runExclusive(lockName(correlationId, keywords),
                () -> response.set(scheduleLocked(request, keywords, autoStart, taskMetadata)));
        if (!acquired) {
            return new TrendDiscoveryScheduleResponse(TASK_NAME, keywords.size(), 0, 0, 0, 0, List.of(),
                    List.of("trend discovery scheduling lock is held by another task-service instance"));
        }
        return response.get();
    }

    private TrendDiscoveryScheduleResponse scheduleLocked(
            TrendDiscoveryScheduleRequest request,
            List<String> keywords,
            boolean autoStart,
            Map<String, Object> taskMetadata) {
        List<DataSourceSeedCandidate> dataSources = limitDataSources(
                taskRepository.findDefaultTaskSeedDataSources(),
                request == null ? null : request.maxDataSources());
        List<TrendDiscoveryScheduledTask> tasks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long created = 0;
        long reused = 0;
        long queued = 0;
        int startPage = positiveOrDefault(request == null ? null : request.startPage(), DEFAULT_START_PAGE);
        int endPage = positiveOrDefault(request == null ? null : request.endPage(), DEFAULT_END_PAGE);
        int fixedDelayMs = nonNegativeOrDefault(request == null ? null : request.fixedDelayMs(), DEFAULT_FIXED_DELAY_MS);

        for (String keyword : keywords) {
            for (DataSourceSeedCandidate dataSource : dataSources) {
                try {
                    EnsureTaskResult result = ensureTask(dataSource, keyword, startPage, endPage, fixedDelayMs, taskMetadata);
                    if (result.created()) {
                        created++;
                    } else {
                        reused++;
                    }
                    boolean accepted = autoStart && taskCommandService.startInternal(result.taskId(), false);
                    if (accepted) {
                        queued++;
                    }
                    tasks.add(new TrendDiscoveryScheduledTask(
                            result.taskId(),
                            dataSource.id(),
                            dataSource.name(),
                            keyword,
                            taskStatus(autoStart, accepted, result.created())));
                } catch (ResponseStatusException ex) {
                    errors.add(errorMessage(keyword, dataSource, ex.getReason()));
                } catch (RuntimeException ex) {
                    errors.add(errorMessage(keyword, dataSource, ex.getMessage()));
                }
            }
        }

        return new TrendDiscoveryScheduleResponse(
                TASK_NAME,
                keywords.size(),
                dataSources.size(),
                created,
                reused,
                queued,
                tasks,
                errors);
    }

    private EnsureTaskResult ensureTask(
            DataSourceSeedCandidate dataSource,
            String keyword,
            int startPage,
            int endPage,
            int fixedDelayMs,
            Map<String, Object> taskMetadata) {
        String name = taskName(dataSource, keyword, taskMetadata);
        return taskRepository.findTaskIdByName(name)
                .map(taskId -> new EnsureTaskResult(taskId, false))
                .orElseGet(() -> new EnsureTaskResult(taskCommandService.create(new TaskCreateRequest(
                        name,
                        dataSource.id(),
                        "BY_PAGE",
                        true,
                        null,
                        CollectionTaskDefaults.TIME_ZONE,
                        startPage,
                        endPage,
                        null,
                        null,
                        keyword,
                        "RANDOM",
                        fixedDelayMs,
                        1000,
                        3000,
                        DEFAULT_TIMEOUT_MS,
                        DEFAULT_MAX_RETRIES,
                        null,
                        true,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        headers(taskMetadata))), true));
    }

    private String headers(Map<String, Object> taskMetadata) {
        if (taskMetadata == null || taskMetadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(taskMetadata);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String taskStatus(boolean autoStart, boolean accepted, boolean created) {
        if (accepted) {
            return "QUEUED";
        }
        if (!autoStart) {
            return created ? "READY" : "REUSED";
        }
        return "SKIPPED_ACTIVE_OR_DISABLED";
    }

    private List<String> sanitizeKeywords(List<String> rawKeywords) {
        if (rawKeywords == null || rawKeywords.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxKeywords);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String keyword : rawKeywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            result.add(keyword.trim());
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private List<DataSourceSeedCandidate> limitDataSources(
            List<DataSourceSeedCandidate> rawDataSources,
            Integer requestMaxDataSources) {
        if (rawDataSources == null || rawDataSources.isEmpty()) {
            return List.of();
        }
        int configuredLimit = Math.max(0, maxDataSources);
        int requestedLimit = requestMaxDataSources == null ? 0 : Math.max(0, requestMaxDataSources);
        int effectiveLimit;
        if (configuredLimit > 0 && requestedLimit > 0) {
            effectiveLimit = Math.min(configuredLimit, requestedLimit);
        } else {
            effectiveLimit = Math.max(configuredLimit, requestedLimit);
        }
        if (effectiveLimit <= 0 || rawDataSources.size() <= effectiveLimit) {
            return List.copyOf(rawDataSources);
        }
        return List.copyOf(rawDataSources.subList(0, effectiveLimit));
    }

    private String taskName(DataSourceSeedCandidate dataSource, String keyword, Map<String, Object> taskMetadata) {
        String discriminator = metadataDiscriminator(taskMetadata);
        String suffix = shortHash(dataSource.id() + ":" + keyword + ":" + discriminator);
        return DISCOVERY_PREFIX
                + " - " + abbreviate(keyword, 60)
                + " - " + abbreviate(dataSource.name(), 60)
                + " - " + suffix;
    }

    private String metadataDiscriminator(Map<String, Object> taskMetadata) {
        if (taskMetadata == null) {
            return "";
        }
        Object mediaRequestBatch = taskMetadata.get("ircsMediaRequestBatch");
        if (mediaRequestBatch instanceof Map<?, ?> batch) {
            Object batchId = batch.get("batchId");
            return batchId == null ? "" : batchId.toString();
        }
        return "";
    }

    private String lockName(String correlationId, List<String> keywords) {
        String seed = StringUtils.hasText(correlationId) ? correlationId.trim() : String.join("|", keywords);
        return "task:trend-discovery:" + shortHash(seed);
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "unknown";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String errorMessage(String keyword, DataSourceSeedCandidate dataSource, String reason) {
        String message = StringUtils.hasText(reason) ? reason : "unknown";
        log.warn("Trend discovery schedule failed for keyword [{}], dataSource [{}]: {}",
                keyword, dataSource.name(), message);
        return "keyword=%s, dataSource=%s: %s".formatted(keyword, dataSource.name(), message);
    }

    private record EnsureTaskResult(UUID taskId, boolean created) {
    }
}
