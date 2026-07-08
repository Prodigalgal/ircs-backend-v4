package com.prodigalgal.ircs.common.normalization;

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
public class LlmCleaningWorkPublisher {

    private static final int KIND_LIMIT = 32;
    private static final int RAW_VALUE_LIMIT = 255;
    private static final int SOURCE_LIMIT = 128;
    private static final int REASON_LIMIT = 128;

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final String defaultSourceService;

    public LlmCleaningWorkPublisher(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            @Value("${spring.application.name:ircs-service}") String defaultSourceService) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.defaultSourceService = normalize(defaultSourceService, SOURCE_LIMIT);
    }

    public void enqueue(String kind, UUID rawId, String rawValue, String reason) {
        enqueue(kind, rawId, rawValue, defaultSourceService, reason);
    }

    public void enqueue(String kind, UUID rawId, String rawValue, String sourceService, String reason) {
        if (!StringUtils.hasText(kind) || rawId == null) {
            return;
        }
        LlmCleaningWorkPayload payload = new LlmCleaningWorkPayload(
                normalize(kind, KIND_LIMIT),
                rawId,
                normalize(rawValue, RAW_VALUE_LIMIT),
                normalize(StringUtils.hasText(sourceService) ? sourceService : defaultSourceService, SOURCE_LIMIT),
                normalize(reason, REASON_LIMIT));
        workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                LlmCleaningWorkTypes.RAW_TERM,
                LlmCleaningWorkTypes.taskId(payload.kind(), rawId),
                rawId.toString(),
                payload.rawValue(),
                serialize(payload)));
        log.debug("Enqueued LLM cleaning work: kind={}, rawId={}, reason={}", payload.kind(), rawId, payload.reason());
    }

    private String serialize(LlmCleaningWorkPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize LLM cleaning work", ex);
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
