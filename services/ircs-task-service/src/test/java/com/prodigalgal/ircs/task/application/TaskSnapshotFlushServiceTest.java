package com.prodigalgal.ircs.task.application;





import com.prodigalgal.ircs.task.infrastructure.TaskDbRuntimeSnapshot;
import com.prodigalgal.ircs.task.runtime.TaskHotKeys;
import com.prodigalgal.ircs.task.infrastructure.JdbcCollectionTaskRepository;
import com.prodigalgal.ircs.task.dto.TaskDetailSummary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class TaskSnapshotFlushServiceTest {

    private final JdbcCollectionTaskRepository taskRepository = org.mockito.Mockito.mock(JdbcCollectionTaskRepository.class);
    private final TaskMasterSnapshotService snapshotService = org.mockito.Mockito.mock(TaskMasterSnapshotService.class);
    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = org.mockito.Mockito.mock(HashOperations.class);
    private final SetOperations<String, String> sets = org.mockito.Mockito.mock(SetOperations.class);
    private final TaskSnapshotFlushService service =
            new TaskSnapshotFlushService(taskRepository, snapshotService, redisTemplate);

    @Test
    void flushesCompletedDirtyMasterIntoDbLedgerAndDeactivatesIt() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageOne = UUID.randomUUID();
        UUID pageTwo = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-06-13T01:00:00Z");
        Instant updatedAt = Instant.parse("2026-06-13T01:00:09Z");
        wireRedisOps();
        when(snapshotService.dirtyMastersBefore(updatedAt, 10)).thenReturn(Set.of(masterTaskId.toString()));
        when(taskRepository.findById(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId, "QUEUED", 1, 2)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.of(snapshot(masterTaskId, dataSourceId, queuedAt, 1, 2)));
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "2",
                "detailScheduled", "4",
                "detailCompleted", "4",
                "detailSucceeded", "3",
                "detailFailed", "1",
                "totalItems", "10",
                "updatedAt", Long.toString(updatedAt.toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageOne.toString(), pageTwo.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageOne))).thenReturn(Map.of(
                "pageNumber", "1",
                "detailScheduled", "2",
                "detailCompleted", "2",
                "detailFailed", "0"));
        when(hashes.entries(TaskHotKeys.pageState(pageTwo))).thenReturn(Map.of(
                "pageNumber", "2",
                "detailScheduled", "2",
                "detailCompleted", "2",
                "detailFailed", "1"));

        TaskSnapshotFlushResult result = service.flushDirtyMasters(updatedAt, 10);

        assertThat(result).isEqualTo(new TaskSnapshotFlushResult(1, 1, 0));
        TaskDbRuntimeSnapshot ledger = captureLedger();
        assertThat(ledger.masterTaskId()).isEqualTo(masterTaskId);
        assertThat(ledger.status()).isEqualTo("FAILED");
        assertThat(ledger.currentPage()).isEqualTo(2);
        assertThat(ledger.totalFound()).isEqualTo(10);
        assertThat(ledger.processed()).isEqualTo(4);
        assertThat(ledger.success()).isEqualTo(3);
        assertThat(ledger.failed()).isEqualTo(1);
        assertThat(ledger.startedAt()).isEqualTo(queuedAt);
        assertThat(ledger.endedAt()).isEqualTo(updatedAt);
        verify(snapshotService).markClean(masterTaskId);
        verify(snapshotService).deactivate(masterTaskId);
    }

    @Test
    void keepsManualPauseWhenRuntimeIsOnlyRunning() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageOne = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T01:03:00Z");
        wireRedisOps();
        when(taskRepository.findById(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId, "PAUSED", 1, 2)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.of(snapshot(masterTaskId, dataSourceId, now, 1, 2)));
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "1",
                "detailScheduled", "2",
                "detailCompleted", "1",
                "detailSucceeded", "1",
                "detailFailed", "0",
                "updatedAt", Long.toString(now.toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageOne.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageOne))).thenReturn(Map.of(
                "pageNumber", "1",
                "detailScheduled", "2",
                "detailCompleted", "1",
                "detailFailed", "0"));

        assertThat(service.flushOne(masterTaskId)).isTrue();

        TaskDbRuntimeSnapshot ledger = captureLedger();
        assertThat(ledger.status()).isEqualTo("PAUSED");
        assertThat(ledger.endedAt()).isNull();
        verify(snapshotService).markClean(masterTaskId);
        verify(snapshotService, never()).deactivate(masterTaskId);
    }

    @Test
    void failedPageTerminalStateFlushesFailedLedger() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageOne = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T01:04:00Z");
        wireRedisOps();
        when(taskRepository.findById(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId, "RUNNING", 1, 1)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.of(snapshot(masterTaskId, dataSourceId, now, 1, 1)));
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "1",
                "pageFailed", "1",
                "detailScheduled", "0",
                "detailCompleted", "0",
                "detailSucceeded", "0",
                "detailFailed", "0",
                "lastError", "source timeout",
                "updatedAt", Long.toString(now.toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageOne.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageOne))).thenReturn(Map.of(
                "pageNumber", "1",
                "detailScheduled", "0",
                "detailCompleted", "0",
                "detailFailed", "0",
                "status", "FAILED",
                "lastError", "source timeout"));

        assertThat(service.flushOne(masterTaskId)).isTrue();

        TaskDbRuntimeSnapshot ledger = captureLedger();
        assertThat(ledger.status()).isEqualTo("FAILED");
        assertThat(ledger.lastError()).isEqualTo("source timeout");
        assertThat(ledger.endedAt()).isEqualTo(now);
        verify(snapshotService).deactivate(masterTaskId);
    }

    @Test
    void openEndedResumeUsesRemainingPagesFromRuntimeStartPage() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID page = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-19T09:22:30Z");
        wireRedisOps();
        when(taskRepository.findById(masterTaskId))
                .thenReturn(Optional.of(task(masterTaskId, dataSourceId, "RUNNING", 1, 0, 54)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.of(new TaskMasterSnapshot(
                masterTaskId,
                dataSourceId,
                "Codex task",
                "COMPLETED",
                true,
                54,
                null,
                1,
                1,
                1,
                0,
                20,
                20,
                20,
                0,
                null,
                masterTaskId.toString(),
                now.minusSeconds(300),
                now)));
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.ofEntries(
                Map.entry("status", "COMPLETED"),
                Map.entry("startPage", "54"),
                Map.entry("pageScheduled", "1"),
                Map.entry("pageDiscovered", "1"),
                Map.entry("pageCompleted", "1"),
                Map.entry("detailScheduled", "20"),
                Map.entry("detailCompleted", "20"),
                Map.entry("detailSucceeded", "20"),
                Map.entry("detailFailed", "0"),
                Map.entry("totalPages", "4191"),
                Map.entry("totalItems", "83814"),
                Map.entry("updatedAt", Long.toString(now.toEpochMilli()))));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(page.toString()));
        when(hashes.entries(TaskHotKeys.pageState(page))).thenReturn(Map.of(
                "pageNumber", "54",
                "detailScheduled", "20",
                "detailCompleted", "20",
                "detailFailed", "0",
                "status", "COMPLETED"));

        assertThat(service.flushOne(masterTaskId)).isTrue();

        TaskDbRuntimeSnapshot ledger = captureLedger();
        assertThat(ledger.status()).isEqualTo("RUNNING");
        assertThat(ledger.currentPage()).isEqualTo(54);
        assertThat(ledger.totalFound()).isEqualTo(83814);
        assertThat(ledger.endedAt()).isNull();
        verify(snapshotService, never()).deactivate(masterTaskId);
    }

    @Test
    void cleansDirtyMarkerWhenDbTaskNoLongerExists() {
        UUID masterTaskId = UUID.randomUUID();
        wireRedisOps();
        when(taskRepository.findById(masterTaskId)).thenReturn(Optional.empty());
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.empty());
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of());
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of());

        assertThat(service.flushOne(masterTaskId)).isFalse();

        verify(taskRepository, never()).flushRuntimeLedger(org.mockito.Mockito.any());
        verify(snapshotService).markClean(masterTaskId);
        verify(snapshotService).deactivate(masterTaskId);
    }

    private TaskDbRuntimeSnapshot captureLedger() {
        ArgumentCaptor<TaskDbRuntimeSnapshot> captor = ArgumentCaptor.forClass(TaskDbRuntimeSnapshot.class);
        verify(taskRepository).flushRuntimeLedger(captor.capture());
        return captor.getValue();
    }

    private TaskDetailSummary task(UUID taskId, UUID dataSourceId, String status, int startPage, int endPage) {
        return task(taskId, dataSourceId, status, startPage, endPage, startPage);
    }

    private TaskDetailSummary task(
            UUID taskId,
            UUID dataSourceId,
            String status,
            int startPage,
            int endPage,
            int currentPage) {
        return new TaskDetailSummary(
                taskId,
                "Codex task",
                status,
                true,
                null,
                "Asia/Shanghai",
                dataSourceId,
                "Codex source",
                "BY_PAGE",
                startPage,
                endPage,
                currentPage,
                null,
                null,
                null,
                "FIXED",
                500,
                null,
                null,
                10000,
                3,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                "{}",
                null,
                Instant.parse("2026-06-13T00:00:00Z"),
                Instant.parse("2026-06-13T00:00:00Z"),
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                null);
    }

    private TaskMasterSnapshot snapshot(UUID masterTaskId, UUID dataSourceId, Instant queuedAt, int startPage, int endPage) {
        return new TaskMasterSnapshot(
                masterTaskId,
                dataSourceId,
                "Codex task",
                "QUEUED",
                false,
                startPage,
                endPage,
                endPage - startPage + 1L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                masterTaskId.toString(),
                queuedAt,
                queuedAt);
    }

    private void wireRedisOps() {
        when(redisTemplate.opsForHash()).thenReturn(hashes);
        when(redisTemplate.opsForSet()).thenReturn(sets);
    }
}
