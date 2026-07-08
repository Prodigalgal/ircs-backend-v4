package com.prodigalgal.ircs.ops.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import com.prodigalgal.ircs.contracts.task.TaskRuntimeHotKeys;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewItemResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class TaskRuntimeOverviewServiceTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final SetOperations<String, String> sets = org.mockito.Mockito.mock(SetOperations.class);
    private final ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
    private final HashOperations<String, Object, Object> hashes = org.mockito.Mockito.mock(HashOperations.class);
    private final ZSetOperations<String, String> zsets = org.mockito.Mockito.mock(ZSetOperations.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-13T00:00:10Z"), ZoneOffset.UTC);
    private final TaskRuntimeOverviewService service = new TaskRuntimeOverviewService(
            redisTemplate,
            objectMapper,
            runtimeConfig,
            clock);

    @Test
    void summarizesActiveMasterRuntimeFromHotSnapshotAndState() throws Exception {
        when(runtimeConfig.boundedIntValue("app.ops.task-runtime.overview.max-limit", 200, 1, 1000))
                .thenReturn(10);
        when(runtimeConfig.positiveDurationValue("app.ops.task-runtime.overview.cache-ttl", Duration.ofSeconds(5)))
                .thenReturn(Duration.ofSeconds(5));
        UUID masterTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-13T00:00:00Z");
        TaskMasterSnapshot snapshot = new TaskMasterSnapshot(
                masterTaskId,
                dataSourceId,
                "Codex source task",
                "QUEUED",
                false,
                1,
                5,
                5,
                0,
                0,
                0,
                10,
                3,
                3,
                0,
                null,
                "corr-1",
                now,
                now);

        wireRedisOps();
        when(sets.members(TaskRuntimeHotKeys.activeMasters()))
                .thenReturn(Set.of(masterTaskId.toString(), "not-a-uuid"));
        when(values.get(TaskRuntimeHotKeys.masterSnapshot(masterTaskId)))
                .thenReturn(objectMapper.writeValueAsString(snapshot));
        when(hashes.entries(TaskRuntimeHotKeys.masterState(masterTaskId))).thenReturn(Map.of(
                "status", "RUNNING",
                "pageDiscovered", "2",
                "detailScheduled", "10",
                "detailCompleted", "4",
                "detailSucceeded", "3",
                "detailFailed", "1",
                "lastError", "detail timeout",
                "updatedAt", Long.toString(now.plusSeconds(5).toEpochMilli())));
        when(zsets.zCard(TaskRuntimeHotKeys.dirtyMasters())).thenReturn(2L);

        TaskRuntimeOverviewResponse overview = service.currentOverview(20);

        assertThat(overview.requestedLimit()).isEqualTo(10);
        assertThat(overview.activeMasterCount()).isEqualTo(1);
        assertThat(overview.returnedMasterCount()).isEqualTo(1);
        assertThat(overview.dirtyMasterCount()).isEqualTo(2);
        assertThat(overview.pageScheduled()).isEqualTo(5);
        assertThat(overview.pageDiscovered()).isEqualTo(2);
        assertThat(overview.detailScheduled()).isEqualTo(10);
        assertThat(overview.detailCompleted()).isEqualTo(4);
        assertThat(overview.detailFailed()).isEqualTo(1);
        assertThat(overview.detailBacklog()).isEqualTo(6);
        assertThat(overview.statusCounts()).containsEntry("RUNNING", 1L);
        assertThat(overview.attentionCounts()).containsEntry("ERROR", 1L);

        TaskRuntimeOverviewItemResponse item = overview.activeMasters().getFirst();
        assertThat(item.masterTaskId()).isEqualTo(masterTaskId);
        assertThat(item.dataSourceId()).isEqualTo(dataSourceId);
        assertThat(item.taskName()).isEqualTo("Codex source task");
        assertThat(item.progressPercent()).isEqualTo(40);
        assertThat(item.attentionLevel()).isEqualTo("ERROR");
        assertThat(item.snapshotPresent()).isTrue();
        assertThat(item.statePresent()).isTrue();
    }

    @Test
    void returnsEmptyOverviewWhenHotStoreIsUnavailable() {
        when(runtimeConfig.boundedIntValue("app.ops.task-runtime.overview.max-limit", 200, 1, 1000))
                .thenReturn(10);
        when(runtimeConfig.positiveDurationValue("app.ops.task-runtime.overview.cache-ttl", Duration.ofSeconds(5)))
                .thenReturn(Duration.ofSeconds(5));
        when(redisTemplate.opsForSet()).thenThrow(new IllegalStateException("hot store unavailable"));

        TaskRuntimeOverviewResponse overview = service.currentOverview(0);

        assertThat(overview.requestedLimit()).isEqualTo(10);
        assertThat(overview.activeMasterCount()).isZero();
        assertThat(overview.activeMasters()).isEmpty();
        assertThat(overview.statusCounts()).isEmpty();
    }

    private void wireRedisOps() {
        when(redisTemplate.opsForSet()).thenReturn(sets);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.opsForHash()).thenReturn(hashes);
        when(redisTemplate.opsForZSet()).thenReturn(zsets);
    }
}
