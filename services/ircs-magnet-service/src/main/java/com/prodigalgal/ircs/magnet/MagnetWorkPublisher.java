package com.prodigalgal.ircs.magnet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.magnet.MagnetWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(RuntimeWorkQueue.class)
@Slf4j
class MagnetWorkPublisher {

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;

    MagnetWorkPublisher(RuntimeWorkQueue workQueue, ObjectMapper objectMapper) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
    }

    void enqueue(MagnetSearchJobSummary job) {
        if (job == null || job.id() == null || job.unifiedVideoId() == null) {
            return;
        }
        MagnetSearchWorkPayload payload = new MagnetSearchWorkPayload(
                job.id(),
                job.unifiedVideoId(),
                job.triggerType());
        workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                MagnetWorkTypes.SEARCH,
                MagnetWorkTypes.searchTaskId(job.id()),
                job.unifiedVideoId().toString(),
                job.triggerType(),
                serialize(payload)));
        log.debug("Enqueued magnet search work: jobId={}, unifiedVideoId={}", job.id(), job.unifiedVideoId());
    }

    private String serialize(MagnetSearchWorkPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize magnet search work", ex);
        }
    }
}
