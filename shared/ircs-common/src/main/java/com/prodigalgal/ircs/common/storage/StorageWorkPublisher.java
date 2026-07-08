package com.prodigalgal.ircs.common.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(RuntimeWorkQueue.class)
@Slf4j
public class StorageWorkPublisher {

    private static final int SOURCE_LIMIT = 128;
    private static final int REASON_LIMIT = 128;

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final String defaultSourceService;

    public StorageWorkPublisher(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            @Value("${spring.application.name:ircs-service}") String defaultSourceService) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.defaultSourceService = normalize(defaultSourceService, SOURCE_LIMIT);
    }

    public void enqueueAvatarSync(UUID memberId, String reason) {
        if (memberId == null) {
            return;
        }
        enqueue(
                StorageWorkTypes.AVATAR_SYNC,
                StorageWorkTypes.avatarTaskId(memberId),
                memberId,
                reason);
    }

    public void enqueueCoverR2Sync(UUID coverImageId, String reason) {
        if (coverImageId == null) {
            return;
        }
        enqueue(
                StorageWorkTypes.COVER_R2_SYNC,
                StorageWorkTypes.coverR2TaskId(coverImageId),
                coverImageId,
                reason);
    }

    private void enqueue(String taskType, String taskId, UUID entityId, String reason) {
        if (entityId == null) {
            return;
        }
        StorageWorkPayload payload = new StorageWorkPayload(
                entityId,
                defaultSourceService,
                normalize(reason, REASON_LIMIT));
        workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                taskType,
                taskId,
                entityId.toString(),
                payload.reason(),
                serialize(payload)));
        log.debug("Enqueued storage runtime work: taskType={}, entityId={}, reason={}", taskType, entityId, payload.reason());
    }

    private String serialize(StorageWorkPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize storage work", ex);
        }
    }

    private static String normalize(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
