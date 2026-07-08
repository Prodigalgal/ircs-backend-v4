package com.prodigalgal.ircs.task.application;


import com.prodigalgal.ircs.task.runtime.TaskHotKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
class TaskMasterSnapshotService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    TaskMasterSnapshotService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.task.snapshot.ttl:PT24H}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    void put(TaskMasterSnapshot snapshot) {
        resetPreviousRuntimeForQueuedCommand(snapshot);
        String json = write(snapshot);
        String snapshotKey = TaskHotKeys.masterSnapshot(snapshot.masterTaskId());
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            redisTemplate.opsForValue().set(snapshotKey, json);
        } else {
            redisTemplate.opsForValue().set(snapshotKey, json, ttl);
        }
        redisTemplate.opsForSet().add(TaskHotKeys.activeMasters(), snapshot.masterTaskId().toString());
        initMasterState(snapshot);
        if (snapshot.dataSourceId() != null) {
            redisTemplate.opsForSet().add(
                    TaskHotKeys.sourceMasters(snapshot.dataSourceId()),
                    snapshot.masterTaskId().toString());
        }
        markDirty(snapshot.masterTaskId(), snapshot.updatedAt());
    }

    private void resetPreviousRuntimeForQueuedCommand(TaskMasterSnapshot snapshot) {
        if (!"QUEUED".equalsIgnoreCase(snapshot.status())) {
            return;
        }
        UUID masterTaskId = snapshot.masterTaskId();
        List<String> keys = new ArrayList<>();
        keys.add(TaskHotKeys.masterSnapshot(masterTaskId));
        keys.add(TaskHotKeys.masterState(masterTaskId));
        keys.add(TaskHotKeys.masterDiscoveredPages(masterTaskId));
        keys.add(TaskHotKeys.masterScheduledPages(masterTaskId));

        Set<String> pageTaskIds = redisTemplate.opsForSet().members(TaskHotKeys.masterDiscoveredPages(masterTaskId));
        if (pageTaskIds != null) {
            for (String pageTaskId : pageTaskIds) {
                UUID parsed = parseUuid(pageTaskId);
                if (parsed != null) {
                    keys.add(TaskHotKeys.pageState(parsed));
                    keys.add(TaskHotKeys.pageCompletedDetails(parsed));
                    keys.add(TaskHotKeys.pageFailedDetails(parsed));
                    keys.add(TaskHotKeys.pageFailedDetailErrors(parsed));
                }
            }
        }
        redisTemplate.delete(keys);
        redisTemplate.opsForZSet().remove(TaskHotKeys.dirtyMasters(), masterTaskId.toString());
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

    private void initMasterState(TaskMasterSnapshot snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("masterTaskId", snapshot.masterTaskId().toString());
        values.put("status", snapshot.status() == null || snapshot.status().isBlank() ? "QUEUED" : snapshot.status());
        values.put("startPage", Integer.toString(snapshot.startPage()));
        values.put("pageScheduled", Long.toString(snapshot.pageScheduled()));
        values.put("pageCompleted", Long.toString(snapshot.pageCompleted()));
        values.put("pageSucceeded", Long.toString(snapshot.pageSucceeded()));
        values.put("pageFailed", Long.toString(snapshot.pageFailed()));
        values.put("detailScheduled", Long.toString(snapshot.detailScheduled()));
        values.put("detailCompleted", Long.toString(snapshot.detailCompleted()));
        values.put("detailSucceeded", Long.toString(snapshot.detailSucceeded()));
        values.put("detailFailed", Long.toString(snapshot.detailFailed()));
        if (snapshot.correlationId() != null && !snapshot.correlationId().isBlank()) {
            values.put("correlationId", snapshot.correlationId());
        }
        Instant queuedAt = snapshot.queuedAt() == null ? Instant.now() : snapshot.queuedAt();
        Instant updatedAt = snapshot.updatedAt() == null ? queuedAt : snapshot.updatedAt();
        values.put("queuedAt", Long.toString(queuedAt.toEpochMilli()));
        values.put("updatedAt", Long.toString(updatedAt.toEpochMilli()));
        if (snapshot.dataSourceId() != null) {
            values.put("dataSourceId", snapshot.dataSourceId().toString());
        }
        if (snapshot.endPage() != null) {
            values.put("endPage", Integer.toString(snapshot.endPage()));
        }
        if (snapshot.lastError() != null && !snapshot.lastError().isBlank()) {
            values.put("lastError", snapshot.lastError());
        }
        redisTemplate.opsForHash().putAll(TaskHotKeys.masterState(snapshot.masterTaskId()), values);
    }

    Optional<TaskMasterSnapshot> find(UUID masterTaskId) {
        String json = redisTemplate.opsForValue().get(TaskHotKeys.masterSnapshot(masterTaskId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TaskMasterSnapshot.class));
        } catch (Exception ex) {
            log.warn("Invalid task master snapshot in Redis: masterTaskId={}", masterTaskId, ex);
            return Optional.empty();
        }
    }

    void markDirty(UUID masterTaskId, Instant at) {
        Instant effectiveAt = at == null ? Instant.now() : at;
        redisTemplate.opsForZSet().add(
                TaskHotKeys.dirtyMasters(),
                masterTaskId.toString(),
                effectiveAt.toEpochMilli());
    }

    java.util.Set<String> dirtyMastersBefore(Instant cutoff, int batchSize) {
        Instant effectiveCutoff = cutoff == null ? Instant.now() : cutoff;
        int safeBatchSize = Math.max(1, batchSize);
        java.util.Set<String> values = redisTemplate.opsForZSet().rangeByScore(
                TaskHotKeys.dirtyMasters(),
                0,
                effectiveCutoff.toEpochMilli(),
                0,
                safeBatchSize);
        return values == null ? java.util.Set.of() : values;
    }

    void markClean(UUID masterTaskId) {
        redisTemplate.opsForZSet().remove(TaskHotKeys.dirtyMasters(), masterTaskId.toString());
    }

    void deactivate(UUID masterTaskId) {
        redisTemplate.opsForSet().remove(TaskHotKeys.activeMasters(), masterTaskId.toString());
    }

    private String write(TaskMasterSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize task master snapshot", ex);
        }
    }
}
