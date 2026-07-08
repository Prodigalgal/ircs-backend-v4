package com.prodigalgal.ircs.task.application;


import com.prodigalgal.ircs.task.runtime.TaskHotKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class TaskMasterSnapshotServiceTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
    private final HashOperations<String, Object, Object> hashes = org.mockito.Mockito.mock(HashOperations.class);
    private final SetOperations<String, String> sets = org.mockito.Mockito.mock(SetOperations.class);
    private final ZSetOperations<String, String> zsets = org.mockito.Mockito.mock(ZSetOperations.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void storesSnapshotAndIndexesActiveMaster() {
        wireRedisOps();
        TaskMasterSnapshotService service = new TaskMasterSnapshotService(redisTemplate, objectMapper, Duration.ofHours(1));
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");

        service.put(new TaskMasterSnapshot(
                masterTaskId,
                dataSourceId,
                "Codex task",
                "QUEUED",
                false,
                1,
                2,
                2,
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
                now));

        verify(values).set(
                org.mockito.Mockito.eq(TaskHotKeys.masterSnapshot(masterTaskId)),
                org.mockito.Mockito.contains("\"status\":\"QUEUED\""),
                org.mockito.Mockito.eq(Duration.ofHours(1)));
        verify(sets).add(TaskHotKeys.activeMasters(), masterTaskId.toString());
        verify(sets).add(TaskHotKeys.sourceMasters(dataSourceId), masterTaskId.toString());
        verify(zsets).add(TaskHotKeys.dirtyMasters(), masterTaskId.toString(), (double) now.toEpochMilli());
    }

    @Test
    void cleansPreviousRuntimeBeforeResumeQueueCommand() {
        wireRedisOps();
        TaskMasterSnapshotService service = new TaskMasterSnapshotService(redisTemplate, objectMapper, Duration.ofHours(1));
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");
        when(sets.members(TaskHotKeys.masterDiscoveredPages(masterTaskId))).thenReturn(Set.of(pageTaskId.toString()));

        service.put(new TaskMasterSnapshot(
                masterTaskId,
                UUID.randomUUID(),
                "Codex task",
                "QUEUED",
                true,
                1,
                null,
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
                now));

        verify(redisTemplate).delete(org.mockito.ArgumentMatchers.<java.util.Collection<String>>argThat(keys ->
                keys.contains(TaskHotKeys.masterSnapshot(masterTaskId))
                        && keys.contains(TaskHotKeys.masterState(masterTaskId))
                        && keys.contains(TaskHotKeys.masterScheduledPages(masterTaskId))
                        && keys.contains(TaskHotKeys.masterDiscoveredPages(masterTaskId))
                        && keys.contains(TaskHotKeys.pageState(pageTaskId))
                        && keys.contains(TaskHotKeys.pageCompletedDetails(pageTaskId))
                        && keys.contains(TaskHotKeys.pageFailedDetails(pageTaskId))
                        && keys.contains(TaskHotKeys.pageFailedDetailErrors(pageTaskId))));
        verify(zsets).remove(TaskHotKeys.dirtyMasters(), masterTaskId.toString());
    }

    @Test
    void readsSnapshotWhenRedisContainsJson() throws Exception {
        wireRedisOps();
        TaskMasterSnapshotService service = new TaskMasterSnapshotService(redisTemplate, objectMapper, Duration.ofHours(1));
        UUID masterTaskId = UUID.randomUUID();
        TaskMasterSnapshot snapshot = new TaskMasterSnapshot(
                masterTaskId,
                UUID.randomUUID(),
                "Codex task",
                "QUEUED",
                true,
                3,
                null,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z"),
                Instant.parse("2026-06-13T00:00:00Z"));
        when(values.get(TaskHotKeys.masterSnapshot(masterTaskId)))
                .thenReturn(objectMapper.writeValueAsString(snapshot));

        Optional<TaskMasterSnapshot> found = service.find(masterTaskId);

        assertThat(found).contains(snapshot);
    }

    @Test
    void readsAndCleansDirtyMasterIndex() {
        wireRedisOps();
        TaskMasterSnapshotService service = new TaskMasterSnapshotService(redisTemplate, objectMapper, Duration.ofHours(1));
        UUID masterTaskId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-06-13T00:00:00Z");
        when(zsets.rangeByScore(TaskHotKeys.dirtyMasters(), 0, cutoff.toEpochMilli(), 0, 50))
                .thenReturn(Set.of(masterTaskId.toString()));

        assertThat(service.dirtyMastersBefore(cutoff, 50)).containsExactly(masterTaskId.toString());
        service.markClean(masterTaskId);
        service.deactivate(masterTaskId);

        verify(zsets).remove(TaskHotKeys.dirtyMasters(), masterTaskId.toString());
        verify(sets).remove(TaskHotKeys.activeMasters(), masterTaskId.toString());
    }

    private void wireRedisOps() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.opsForHash()).thenReturn(hashes);
        when(redisTemplate.opsForSet()).thenReturn(sets);
        when(redisTemplate.opsForZSet()).thenReturn(zsets);
    }
}
