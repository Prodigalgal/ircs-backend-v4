package com.prodigalgal.ircs.task.application;







import com.prodigalgal.ircs.task.dto.TaskRuntimeDetailResponse;
import com.prodigalgal.ircs.task.dto.TaskMasterRuntimeSummary;
import com.prodigalgal.ircs.task.runtime.TaskHotKeys;
import com.prodigalgal.ircs.task.domain.TaskRuntimeStatus;
import com.prodigalgal.ircs.task.dto.TaskPageRuntimeSummary;
import com.prodigalgal.ircs.task.runtime.TaskRuntimeFields;
import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import com.prodigalgal.ircs.task.dto.TaskDetailSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskRuntimeReadService {

    private static final int DEFAULT_PAGE_LIMIT = 100;
    private static final int MAX_PAGE_LIMIT = 500;
    private static final int MAX_DETAIL_IDS_PER_PAGE = 100;

    private final TaskQueryService taskQueryService;
    private final TaskMasterSnapshotService snapshotService;
    private final StringRedisTemplate redisTemplate;

    public Optional<TaskRuntimeDetailResponse> find(UUID masterTaskId, int pageOffset, int pageLimit, int detailLimit) {
        Optional<TaskDetailSummary> task = taskQueryService.findOne(masterTaskId);
        Optional<TaskMasterSnapshot> snapshot = snapshotService.find(masterTaskId);
        Map<String, String> masterState = hash(TaskHotKeys.masterState(masterTaskId));
        List<UUID> runtimePageIds = runtimePageIds(masterTaskId);

        if (task.isEmpty() && snapshot.isEmpty() && masterState.isEmpty() && runtimePageIds.isEmpty()) {
            return Optional.empty();
        }

        List<TaskPageRuntimeSummary> allPages = runtimePageIds.stream()
                .map(pageTaskId -> page(pageTaskId, detailLimit))
                .sorted(Comparator
                        .comparing((TaskPageRuntimeSummary page) -> page.pageNumber() == null ? Integer.MAX_VALUE : page.pageNumber())
                        .thenComparing(page -> page.pageTaskId().toString()))
                .toList();

        int safeOffset = Math.max(0, pageOffset);
        int safeLimit = sanitizeLimit(pageLimit);
        List<TaskPageRuntimeSummary> visiblePages = allPages.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .toList();

        TaskDetailSummary taskDetail = task.orElse(null);
        TaskMasterSnapshot rawSnapshot = snapshot.orElse(null);
        TaskMasterRuntimeSummary master = master(masterTaskId, taskDetail, rawSnapshot, masterState, allPages);
        TaskMasterSnapshot effectiveSnapshot = effectiveSnapshot(
                masterTaskId,
                rawSnapshot,
                masterState,
                master);
        return Optional.of(new TaskRuntimeDetailResponse(
                masterTaskId,
                taskDetail,
                effectiveSnapshot,
                master,
                visiblePages,
                safeOffset,
                safeLimit,
                visiblePages.size(),
                allPages.size(),
                snapshot.isPresent(),
                !masterState.isEmpty(),
                Instant.now()));
    }

    private TaskMasterSnapshot effectiveSnapshot(
            UUID masterTaskId,
            TaskMasterSnapshot snapshot,
            Map<String, String> masterState,
            TaskMasterRuntimeSummary master) {
        if (snapshot == null && masterState.isEmpty()) {
            return null;
        }
        long pageSucceeded = longValue(
                masterState,
                TaskRuntimeFields.PAGE_SUCCEEDED,
                snapshot == null ? Math.max(0, master.pageCompleted() - master.pageFailed()) : snapshot.pageSucceeded());
        return new TaskMasterSnapshot(
                masterTaskId,
                master.dataSourceId(),
                master.taskName(),
                master.status(),
                master.resume(),
                master.startPage(),
                master.endPage(),
                master.pageScheduled(),
                master.pageCompleted(),
                pageSucceeded,
                master.pageFailed(),
                master.detailScheduled(),
                master.detailCompleted(),
                master.detailSucceeded(),
                master.detailFailed(),
                master.lastError(),
                master.correlationId(),
                master.queuedAt(),
                master.updatedAt());
    }

    private TaskMasterRuntimeSummary master(
            UUID masterTaskId,
            TaskDetailSummary task,
            TaskMasterSnapshot snapshot,
            Map<String, String> masterState,
            List<TaskPageRuntimeSummary> pages
    ) {
        long detailScheduled = longValue(masterState, TaskRuntimeFields.DETAIL_SCHEDULED, snapshot == null ? 0 : snapshot.detailScheduled());
        long detailCompleted = longValue(masterState, TaskRuntimeFields.DETAIL_COMPLETED, snapshot == null ? 0 : snapshot.detailCompleted());
        long detailSucceeded = longValue(masterState, TaskRuntimeFields.DETAIL_SUCCEEDED, snapshot == null ? 0 : snapshot.detailSucceeded());
        long detailFailed = longValue(masterState, TaskRuntimeFields.DETAIL_FAILED, snapshot == null ? 0 : snapshot.detailFailed());
        long pageScheduled = longValue(
                masterState,
                TaskRuntimeFields.PAGE_SCHEDULED,
                snapshot == null ? configuredPageCount(task) : snapshot.pageScheduled());
        long pageDiscovered = longValue(masterState, TaskRuntimeFields.PAGE_DISCOVERED, pages.size());
        long derivedPageCompleted = pages.stream()
                .filter(page -> page.detailScheduled() > 0 && page.detailCompleted() >= page.detailScheduled())
                .count();
        long derivedPageFailed = pages.stream()
                .filter(page -> page.detailFailed() > 0 || TaskRuntimeStatus.FAILED.value().equalsIgnoreCase(page.status()))
                .count();
        long pageCompleted = longValue(masterState, TaskRuntimeFields.PAGE_COMPLETED, derivedPageCompleted);
        long pageFailed = longValue(masterState, TaskRuntimeFields.PAGE_FAILED, derivedPageFailed);

        return new TaskMasterRuntimeSummary(
                firstNonBlank(masterState.get(TaskRuntimeFields.STATUS), snapshot == null ? null : snapshot.status(), task == null ? null : task.status()),
                snapshot == null ? task == null ? null : task.dataSourceId() : snapshot.dataSourceId(),
                firstNonBlank(snapshot == null ? null : snapshot.taskName(), task == null ? null : task.name()),
                snapshot != null && snapshot.resume(),
                snapshot == null ? safeStartPage(task) : snapshot.startPage(),
                snapshot == null ? task == null ? null : task.endPage() : snapshot.endPage(),
                pageScheduled,
                pageDiscovered,
                pageCompleted,
                pageFailed,
                detailScheduled,
                detailCompleted,
                detailSucceeded,
                detailFailed,
                intValue(masterState, TaskRuntimeFields.TOTAL_PAGES, null),
                intValue(masterState, TaskRuntimeFields.TOTAL_ITEMS, null),
                firstNonBlank(masterState.get(TaskRuntimeFields.LAST_ERROR), snapshot == null ? null : snapshot.lastError(), task == null ? null : task.lastErrorMessage()),
                firstNonBlank(snapshot == null ? null : snapshot.correlationId(), masterTaskId.toString()),
                snapshot == null ? task == null ? null : task.statStartTime() : snapshot.queuedAt(),
                instantFromEpoch(masterState, TaskRuntimeFields.UPDATED_AT).orElse(snapshot == null ? task == null ? null : task.updatedAt() : snapshot.updatedAt()),
                masterState);
    }

    private TaskPageRuntimeSummary page(UUID pageTaskId, int detailLimit) {
        Map<String, String> pageState = hash(TaskHotKeys.pageState(pageTaskId));
        List<String> completedDetailIds = completedDetailIds(pageTaskId, detailLimit);
        List<String> failedDetailIds = failedDetailIds(pageTaskId, detailLimit);
        Map<String, String> failedDetailErrors = failedDetailErrors(pageTaskId, failedDetailIds);
        long completedCount = longValue(pageState, TaskRuntimeFields.DETAIL_COMPLETED, completedDetailIds.size());
        long scheduled = longValue(pageState, TaskRuntimeFields.DETAIL_SCHEDULED, 0);
        long failed = longValue(pageState, TaskRuntimeFields.DETAIL_FAILED, 0);
        String status = firstNonBlank(pageState.get(TaskRuntimeFields.STATUS), derivedPageStatus(scheduled, completedCount, failed));
        long backlog = pendingCount(scheduled, completedCount);

        return new TaskPageRuntimeSummary(
                pageTaskId,
                intValue(pageState, TaskRuntimeFields.PAGE_NUMBER, null),
                scheduled,
                completedCount,
                longValue(pageState, TaskRuntimeFields.DETAIL_SUCCEEDED, 0),
                failed,
                status,
                pageState.get(TaskRuntimeFields.LAST_ERROR),
                instantFromEpoch(pageState, TaskRuntimeFields.UPDATED_AT).orElse(null),
                safeInt(completedCount),
                completedDetailIds,
                safeInt(Math.max(failed, failedDetailIds.size())),
                failedDetailIds,
                failedDetailErrors,
                backlog,
                percent(completedCount, scheduled),
                attentionLevel(status, failed, backlog, pageState.get(TaskRuntimeFields.LAST_ERROR)),
                pageState);
    }

    private Map<String, String> hash(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((keyPart, value) -> {
            if (keyPart != null && value != null) {
                result.put(keyPart.toString(), value.toString());
            }
        });
        return result;
    }

    private List<UUID> runtimePageIds(UUID masterTaskId) {
        Set<String> members = redisTemplate.opsForSet().members(TaskHotKeys.masterDiscoveredPages(masterTaskId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<UUID> pageIds = new ArrayList<>();
        for (String member : members) {
            try {
                pageIds.add(UUID.fromString(member));
            } catch (IllegalArgumentException ignored) {
                // Ignore corrupt Redis members; the raw Redis key remains available for inspection.
            }
        }
        return pageIds;
    }

    private List<String> completedDetailIds(UUID pageTaskId, int detailLimit) {
        return setMembers(TaskHotKeys.pageCompletedDetails(pageTaskId), detailLimit);
    }

    private List<String> failedDetailIds(UUID pageTaskId, int detailLimit) {
        return setMembers(TaskHotKeys.pageFailedDetails(pageTaskId), detailLimit);
    }

    private List<String> setMembers(String key, int limit) {
        int safeLimit = Math.min(Math.max(0, limit), MAX_DETAIL_IDS_PER_PAGE);
        if (safeLimit == 0) {
            return List.of();
        }
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream()
                .sorted()
                .limit(safeLimit)
                .toList();
    }

    private Map<String, String> failedDetailErrors(UUID pageTaskId, List<String> failedDetailIds) {
        if (failedDetailIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> errors = hash(TaskHotKeys.pageFailedDetailErrors(pageTaskId));
        if (errors.isEmpty()) {
            return Map.of();
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String detailId : failedDetailIds) {
            String error = errors.get(detailId);
            if (error != null && !error.isBlank()) {
                ordered.put(detailId, error);
            }
        }
        return ordered;
    }

    private int sanitizeLimit(int pageLimit) {
        if (pageLimit <= 0) {
            return DEFAULT_PAGE_LIMIT;
        }
        return Math.min(pageLimit, MAX_PAGE_LIMIT);
    }

    private long longValue(Map<String, String> values, String key, long fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer intValue(Map<String, String> values, String key, Integer fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Optional<Instant> instantFromEpoch(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private int safeStartPage(TaskDetailSummary task) {
        return task != null && task.startPage() != null && task.startPage() > 0 ? task.startPage() : 1;
    }

    private long configuredPageCount(TaskDetailSummary task) {
        if (task == null || task.endPage() == null || task.endPage() <= 0) {
            return 0;
        }
        return Math.max(0, task.endPage() - safeStartPage(task) + 1);
    }

    private String derivedPageStatus(long scheduled, long completed, long failed) {
        if (scheduled > 0 && completed >= scheduled) {
            return failed > 0 ? TaskRuntimeStatus.COMPLETED_WITH_ERRORS.value() : TaskRuntimeStatus.COMPLETED.value();
        }
        if (completed > 0) {
            return TaskRuntimeStatus.RUNNING.value();
        }
        return TaskRuntimeStatus.DISCOVERED.value();
    }

    private long pendingCount(long total, long done) {
        return Math.max(0, total - done);
    }

    private int percent(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        return safeInt(Math.min(100, Math.round((done * 100.0d) / total)));
    }

    private String attentionLevel(String status, long failed, long backlog, String lastError) {
        if (failed > 0 || TaskRuntimeStatus.isErrorLike(status)
                || (lastError != null && !lastError.isBlank())) {
            return "ERROR";
        }
        if (backlog > 0 && TaskRuntimeStatus.RUNNING.value().equalsIgnoreCase(status)) {
            return "RUNNING";
        }
        if (backlog > 0) {
            return "WAITING";
        }
        return "OK";
    }

    private int safeInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, (int) value);
    }
}
