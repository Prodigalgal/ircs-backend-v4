package com.prodigalgal.ircs.task.application;





import com.prodigalgal.ircs.task.dto.TaskRuntimeDetailResponse;
import com.prodigalgal.ircs.task.runtime.TaskHotKeys;
import com.prodigalgal.ircs.task.dto.TaskPageRuntimeSummary;
import com.prodigalgal.ircs.task.dto.TaskDetailSummary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class TaskRuntimeReadServiceTest {

    private final TaskQueryService taskQueryService = org.mockito.Mockito.mock(TaskQueryService.class);
    private final TaskMasterSnapshotService snapshotService = org.mockito.Mockito.mock(TaskMasterSnapshotService.class);
    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = org.mockito.Mockito.mock(HashOperations.class);
    private final SetOperations<String, String> sets = org.mockito.Mockito.mock(SetOperations.class);
    private final TaskRuntimeReadService service =
            new TaskRuntimeReadService(taskQueryService, snapshotService, redisTemplate);

    @Test
    void mergesDbSnapshotMasterStateAndPageStateForAdminRuntimeView() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID detailTaskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");

        wireRedisOps();
        when(taskQueryService.findOne(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.of(new TaskMasterSnapshot(
                masterTaskId,
                dataSourceId,
                "Codex task",
                "QUEUED",
                false,
                1,
                3,
                3,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                masterTaskId.toString(),
                now,
                now)));
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "1",
                "detailScheduled", "2",
                "detailCompleted", "1",
                "detailSucceeded", "1",
                "detailFailed", "0",
                "totalPages", "9",
                "totalItems", "88",
                "updatedAt", Long.toString(now.plusSeconds(1).toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageTaskId.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageTaskId))).thenReturn(Map.of(
                "pageNumber", "2",
                "detailScheduled", "2",
                "detailCompleted", "1",
                "detailSucceeded", "1",
                "detailFailed", "0",
                "status", "RUNNING",
                "updatedAt", Long.toString(now.plusSeconds(1).toEpochMilli())));
        when(sets.members(TaskHotKeys.pageCompletedDetails(pageTaskId))).thenReturn(Set.of(detailTaskId.toString()));

        TaskRuntimeDetailResponse response = service.find(masterTaskId, 0, 20, 10).orElseThrow();

        assertThat(response.masterTaskId()).isEqualTo(masterTaskId);
        assertThat(response.redisSnapshotPresent()).isTrue();
        assertThat(response.redisStatePresent()).isTrue();
        assertThat(response.snapshot()).isNotNull();
        assertThat(response.snapshot().status()).isEqualTo("RUNNING");
        assertThat(response.snapshot().detailScheduled()).isEqualTo(2);
        assertThat(response.snapshot().detailCompleted()).isEqualTo(1);
        assertThat(response.snapshot().updatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(response.master().status()).isEqualTo("RUNNING");
        assertThat(response.master().pageScheduled()).isEqualTo(3);
        assertThat(response.master().pageDiscovered()).isEqualTo(1);
        assertThat(response.master().detailScheduled()).isEqualTo(2);
        assertThat(response.master().detailCompleted()).isEqualTo(1);
        assertThat(response.master().totalPages()).isEqualTo(9);
        assertThat(response.pages()).hasSize(1);
        assertThat(response.pages().get(0).pageTaskId()).isEqualTo(pageTaskId);
        assertThat(response.pages().get(0).pageNumber()).isEqualTo(2);
        assertThat(response.pages().get(0).completedDetailTaskIds()).containsExactly(detailTaskId.toString());
        assertThat(response.pages().get(0).detailBacklog()).isEqualTo(1);
        assertThat(response.pages().get(0).progressPercent()).isEqualTo(50);
        assertThat(response.pages().get(0).attentionLevel()).isEqualTo("RUNNING");
    }

    @Test
    void exposesQueuedPageSkeletonBeforeWorkerDiscoversDetails() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");

        wireRedisOps();
        when(taskQueryService.findOne(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.of(new TaskMasterSnapshot(
                masterTaskId,
                dataSourceId,
                "Codex task",
                "QUEUED",
                true,
                1,
                3,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                masterTaskId.toString(),
                now,
                now)));
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageScheduled", "1",
                "pageDiscovered", "0",
                "updatedAt", Long.toString(now.plusSeconds(1).toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageTaskId.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageTaskId))).thenReturn(Map.of(
                "pageNumber", "1",
                "detailScheduled", "0",
                "detailCompleted", "0",
                "detailSucceeded", "0",
                "detailFailed", "0",
                "status", "QUEUED",
                "updatedAt", Long.toString(now.plusSeconds(1).toEpochMilli())));
        when(sets.members(TaskHotKeys.pageCompletedDetails(pageTaskId))).thenReturn(Set.of());

        TaskRuntimeDetailResponse response = service.find(masterTaskId, 0, 20, 10).orElseThrow();

        assertThat(response.master().status()).isEqualTo("RUNNING");
        assertThat(response.master().pageScheduled()).isEqualTo(1);
        assertThat(response.master().pageDiscovered()).isZero();
        assertThat(response.snapshot().pageScheduled()).isEqualTo(1);
        assertThat(response.pages()).hasSize(1);
        TaskPageRuntimeSummary page = response.pages().get(0);
        assertThat(page.pageTaskId()).isEqualTo(pageTaskId);
        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.status()).isEqualTo("QUEUED");
        assertThat(page.detailBacklog()).isZero();
        assertThat(page.progressPercent()).isZero();
    }

    @Test
    void returnsEmptyWhenNoRuntimeOrDbRecordExists() {
        UUID masterTaskId = UUID.randomUUID();
        wireRedisOps();
        when(taskQueryService.findOne(masterTaskId)).thenReturn(Optional.empty());
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.empty());
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of());
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of());

        assertThat(service.find(masterTaskId, 0, 100, 20)).isEmpty();
    }

    @Test
    void countsFailedPageStateAsPageFailure() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");

        wireRedisOps();
        when(taskQueryService.findOne(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.empty());
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "1",
                "pageScheduled", "1",
                "updatedAt", Long.toString(now.toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageTaskId.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageTaskId))).thenReturn(Map.of(
                "pageNumber", "1",
                "detailScheduled", "0",
                "detailCompleted", "0",
                "detailFailed", "0",
                "status", "FAILED",
                "lastError", "source timeout",
                "updatedAt", Long.toString(now.toEpochMilli())));
        when(sets.members(TaskHotKeys.pageCompletedDetails(pageTaskId))).thenReturn(Set.of());

        TaskRuntimeDetailResponse response = service.find(masterTaskId, 0, 20, 10).orElseThrow();

        assertThat(response.master().pageFailed()).isEqualTo(1);
        assertThat(response.pages().get(0).status()).isEqualTo("FAILED");
        assertThat(response.pages().get(0).lastError()).isEqualTo("source timeout");
        assertThat(response.pages().get(0).attentionLevel()).isEqualTo("ERROR");
    }

    @Test
    void exposesFailedDetailSamplesForAdminRuntimeView() {
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID successDetailTaskId = UUID.randomUUID();
        UUID failedDetailTaskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");

        wireRedisOps();
        when(taskQueryService.findOne(masterTaskId)).thenReturn(Optional.of(task(masterTaskId, dataSourceId)));
        when(snapshotService.find(masterTaskId)).thenReturn(Optional.empty());
        when(hashes.entries(TaskHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "1",
                "detailScheduled", "2",
                "detailCompleted", "2",
                "detailSucceeded", "1",
                "detailFailed", "1",
                "updatedAt", Long.toString(now.toEpochMilli())));
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageTaskId.toString()));
        when(hashes.entries(TaskHotKeys.pageState(pageTaskId))).thenReturn(Map.of(
                "pageNumber", "1",
                "detailScheduled", "2",
                "detailCompleted", "2",
                "detailSucceeded", "1",
                "detailFailed", "1",
                "status", "COMPLETED_WITH_ERRORS",
                "lastError", "detail timeout",
                "updatedAt", Long.toString(now.toEpochMilli())));
        when(sets.members(TaskHotKeys.pageCompletedDetails(pageTaskId)))
                .thenReturn(Set.of(successDetailTaskId.toString(), failedDetailTaskId.toString()));
        when(sets.members(TaskHotKeys.pageFailedDetails(pageTaskId)))
                .thenReturn(Set.of(failedDetailTaskId.toString()));
        when(hashes.entries(TaskHotKeys.pageFailedDetailErrors(pageTaskId)))
                .thenReturn(Map.of(failedDetailTaskId.toString(), "vod-9 :: detail timeout"));

        TaskRuntimeDetailResponse response = service.find(masterTaskId, 0, 20, 10).orElseThrow();

        TaskPageRuntimeSummary page = response.pages().get(0);
        assertThat(page.failedDetailTaskCount()).isEqualTo(1);
        assertThat(page.failedDetailTaskIds()).containsExactly(failedDetailTaskId.toString());
        assertThat(page.failedDetailErrors()).containsEntry(failedDetailTaskId.toString(), "vod-9 :: detail timeout");
        assertThat(page.detailBacklog()).isZero();
        assertThat(page.progressPercent()).isEqualTo(100);
        assertThat(page.attentionLevel()).isEqualTo("ERROR");
    }

    private TaskDetailSummary task(UUID taskId, UUID dataSourceId) {
        return new TaskDetailSummary(
                taskId,
                "Codex task",
                "QUEUED",
                true,
                null,
                "Asia/Shanghai",
                dataSourceId,
                "Codex source",
                "BY_PAGE",
                1,
                3,
                1,
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
                Instant.parse("2026-06-13T00:00:00Z"),
                null);
    }

    private void wireRedisOps() {
        when(redisTemplate.opsForHash()).thenReturn(hashes);
        when(redisTemplate.opsForSet()).thenReturn(sets);
    }
}
