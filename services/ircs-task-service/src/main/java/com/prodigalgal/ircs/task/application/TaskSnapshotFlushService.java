package com.prodigalgal.ircs.task.application;




import com.prodigalgal.ircs.task.infrastructure.TaskDbRuntimeSnapshot;
import com.prodigalgal.ircs.task.runtime.TaskHotKeys;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskSnapshotFlushService {

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_STOPPING = "STOPPING";

    private final JdbcCollectionTaskRepository taskRepository;
    private final TaskMasterSnapshotService snapshotService;
    private final StringRedisTemplate redisTemplate;
    @Value("${app.task.queue.max-pages-per-run:0}")
    private int maxPagesPerRun;

    public TaskSnapshotFlushResult flushDirtyMasters(Instant cutoff, int batchSize) {
        Set<String> dirtyMasters = snapshotService.dirtyMastersBefore(cutoff, batchSize);
        if (dirtyMasters.isEmpty()) {
            return new TaskSnapshotFlushResult(0, 0, 0);
        }

        int flushed = 0;
        int failed = 0;
        for (String value : dirtyMasters) {
            UUID masterTaskId = parseUuid(value);
            if (masterTaskId == null) {
                failed++;
                continue;
            }
            try {
                if (flushOne(masterTaskId)) {
                    flushed++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Task snapshot flush failed: masterTaskId={}", masterTaskId, ex);
            }
        }
        return new TaskSnapshotFlushResult(dirtyMasters.size(), flushed, failed);
    }

    public boolean flushOne(UUID masterTaskId) {
        Optional<TaskDetailSummary> task = taskRepository.findById(masterTaskId);
        Optional<TaskMasterSnapshot> snapshot = snapshotService.find(masterTaskId);
        Map<String, String> masterState = hash(TaskHotKeys.masterState(masterTaskId));
        List<TaskFlushPageState> pages = pages(masterTaskId);

        if (task.isEmpty() && snapshot.isEmpty() && masterState.isEmpty() && pages.isEmpty()) {
            snapshotService.markClean(masterTaskId);
            snapshotService.deactivate(masterTaskId);
            return false;
        }
        if (task.isEmpty()) {
            snapshotService.markClean(masterTaskId);
            snapshotService.deactivate(masterTaskId);
            return false;
        }

        TaskDbRuntimeSnapshot ledger = ledger(masterTaskId, task.orElseThrow(), snapshot.orElse(null), masterState, pages);
        taskRepository.flushRuntimeLedger(ledger);
        snapshotService.markClean(masterTaskId);
        if (isTerminal(ledger.status())) {
            snapshotService.deactivate(masterTaskId);
        }
        return true;
    }

    private TaskDbRuntimeSnapshot ledger(
            UUID masterTaskId,
            TaskDetailSummary task,
            TaskMasterSnapshot snapshot,
            Map<String, String> masterState,
            List<TaskFlushPageState> pages
    ) {
        long detailScheduled = longValue(masterState, "detailScheduled", snapshot == null ? 0 : snapshot.detailScheduled());
        long detailCompleted = longValue(masterState, "detailCompleted", snapshot == null ? 0 : snapshot.detailCompleted());
        long detailSucceeded = longValue(masterState, "detailSucceeded", snapshot == null ? 0 : snapshot.detailSucceeded());
        long detailFailed = longValue(masterState, "detailFailed", snapshot == null ? 0 : snapshot.detailFailed());
        long pageDiscovered = longValue(masterState, "pageDiscovered", pages.size());
        long configuredPageCount = configuredPageCount(task, snapshot, masterState);
        long pageCompletedFromPages = pages.stream()
                .filter(TaskFlushPageState::completed)
                .count();
        long pageCompleted = Math.max(
                pageCompletedFromPages,
                longValue(masterState, "pageCompleted", snapshot == null ? 0 : snapshot.pageCompleted()));
        long pageFailed = Math.max(
                pages.stream().filter(TaskFlushPageState::failed).count(),
                longValue(masterState, "pageFailed", snapshot == null ? 0 : snapshot.pageFailed()));
        String runtimeStatus = firstNonBlank(masterState.get("status"), snapshot == null ? null : snapshot.status(), task.status());
        String status = status(task.status(), runtimeStatus, configuredPageCount, pageDiscovered, pageCompleted,
                pageFailed, detailScheduled, detailCompleted, detailFailed);
        Integer currentPage = currentPage(task, pages);
        long totalFound = longValue(masterState, "totalItems", detailScheduled);
        Instant updatedAt = instantFromEpoch(masterState, "updatedAt")
                .orElse(snapshot == null ? Instant.now() : snapshot.updatedAt());
        Instant endedAt = isTerminal(status) ? updatedAt : null;

        return new TaskDbRuntimeSnapshot(
                masterTaskId,
                status,
                currentPage,
                totalFound,
                detailCompleted,
                detailSucceeded,
                detailFailed,
                snapshot == null ? task.statStartTime() : snapshot.queuedAt(),
                endedAt,
                firstNonBlank(masterState.get("lastError"), snapshot == null ? null : snapshot.lastError(), task.lastErrorMessage()),
                Instant.now());
    }

    private List<TaskFlushPageState> pages(UUID masterTaskId) {
        Set<String> members = redisTemplate.opsForSet().members(TaskHotKeys.masterDiscoveredPages(masterTaskId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<TaskFlushPageState> pages = new ArrayList<>();
        for (String member : members) {
            UUID pageTaskId = parseUuid(member);
            if (pageTaskId == null) {
                continue;
            }
            Map<String, String> state = hash(TaskHotKeys.pageState(pageTaskId));
            if (!state.isEmpty()) {
                pages.add(new TaskFlushPageState(
                        intValue(state, "pageNumber", null),
                        longValue(state, "detailScheduled", 0),
                        longValue(state, "detailCompleted", 0),
                        longValue(state, "detailFailed", 0),
                        firstNonBlank(state.get("status"))));
            }
        }
        return pages.stream()
                .sorted(Comparator.comparing(page -> page.pageNumber() == null ? Integer.MAX_VALUE : page.pageNumber()))
                .toList();
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

    private String status(
            String dbStatus,
            String runtimeStatus,
            long pageScheduled,
            long pageDiscovered,
            long pageCompleted,
            long pageFailed,
            long detailScheduled,
            long detailCompleted,
            long detailFailed
    ) {
        String normalizedRuntime = normalizeStatus(runtimeStatus);
        if (STATUS_FAILED.equals(normalizedRuntime)) {
            return STATUS_FAILED;
        }
        if (allScheduledWorkDone(pageScheduled, pageDiscovered, pageCompleted, detailScheduled, detailCompleted)) {
            return pageFailed > 0 || detailFailed > 0 ? STATUS_FAILED : STATUS_COMPLETED;
        }
        if (isManualHoldStatus(dbStatus)) {
            return normalizeStatus(dbStatus);
        }
        if (detailCompleted > 0 || pageDiscovered > 0 || STATUS_RUNNING.equals(normalizedRuntime)) {
            return STATUS_RUNNING;
        }
        return firstNonBlank(normalizedRuntime, STATUS_QUEUED);
    }

    private boolean allScheduledWorkDone(
            long pageScheduled,
            long pageDiscovered,
            long pageCompleted,
            long detailScheduled,
            long detailCompleted
    ) {
        if (pageScheduled <= 0) {
            return false;
        }
        if (detailScheduled <= 0) {
            return pageDiscovered >= pageScheduled && pageCompleted >= pageScheduled;
        }
        return pageDiscovered >= pageScheduled
                && pageCompleted >= pageScheduled
                && detailCompleted >= detailScheduled;
    }

    private long configuredPageCount(TaskDetailSummary task, TaskMasterSnapshot snapshot, Map<String, String> masterState) {
        if (snapshot != null && snapshot.endPage() != null) {
            return capPageCount(Math.max(0, snapshot.endPage() - snapshot.startPage() + 1L));
        }
        if (task.endPage() != null && task.endPage() > 0) {
            int startPage = task.startPage() == null || task.startPage() <= 0 ? 1 : task.startPage();
            return capPageCount(Math.max(0, task.endPage() - startPage + 1L));
        }
        long scheduled = longValue(masterState, "pageScheduled", snapshot == null ? 0 : snapshot.pageScheduled());
        Integer totalPages = intValue(masterState, "totalPages", null);
        if (totalPages != null && totalPages > 0) {
            int runStartPage = runtimeStartPage(task, snapshot, masterState);
            long remainingRunPages = Math.max(0L, totalPages - runStartPage + 1L);
            return capPageCount(Math.max(scheduled, remainingRunPages));
        }
        return capPageCount(scheduled);
    }

    private int runtimeStartPage(TaskDetailSummary task, TaskMasterSnapshot snapshot, Map<String, String> masterState) {
        Integer stateStart = intValue(masterState, "startPage", null);
        if (stateStart != null && stateStart > 0) {
            return stateStart;
        }
        if (snapshot != null && snapshot.startPage() > 0) {
            return snapshot.startPage();
        }
        if (task.currentPage() != null && task.currentPage() > 0) {
            return task.currentPage();
        }
        return task.startPage() != null && task.startPage() > 0 ? task.startPage() : 1;
    }

    private long capPageCount(long pageCount) {
        if (maxPagesPerRun <= 0 || pageCount <= 0) {
            return pageCount;
        }
        return Math.min(pageCount, maxPagesPerRun);
    }

    private Integer currentPage(TaskDetailSummary task, List<TaskFlushPageState> pages) {
        return pages.stream()
                .map(TaskFlushPageState::pageNumber)
                .filter(value -> value != null && value > 0)
                .max(Integer::compareTo)
                .orElse(task.currentPage() == null ? task.startPage() : task.currentPage());
    }

    private boolean isTerminal(String status) {
        String normalized = normalizeStatus(status);
        return STATUS_COMPLETED.equals(normalized) || STATUS_FAILED.equals(normalized);
    }

    private boolean isManualHoldStatus(String status) {
        String normalized = normalizeStatus(status);
        return STATUS_PAUSED.equals(normalized) || STATUS_STOPPING.equals(normalized);
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase();
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private record TaskFlushPageState(
            Integer pageNumber,
            long detailScheduled,
            long detailCompleted,
            long detailFailed,
            String status
    ) {
        boolean completed() {
            String status = normalizedStatus();
            return failed()
                    || STATUS_COMPLETED.equals(status)
                    || "COMPLETED_WITH_ERRORS".equals(status)
                    || (detailScheduled <= 0 && "DISCOVERED".equals(status))
                    || (detailScheduled > 0 && detailCompleted >= detailScheduled);
        }

        boolean failed() {
            return STATUS_FAILED.equals(normalizedStatus());
        }

        private String normalizedStatus() {
            return status == null || status.isBlank() ? null : status.trim().toUpperCase();
        }
    }
}
