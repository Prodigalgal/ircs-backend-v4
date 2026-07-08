package com.prodigalgal.ircs.common.aggregation;

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
public class AggregationWorkPublisher {

    private static final int SOURCE_LIMIT = 128;
    private static final int REASON_LIMIT = 128;

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final String defaultSourceService;

    public AggregationWorkPublisher(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            @Value("${spring.application.name:ircs-service}") String defaultSourceService) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.defaultSourceService = normalize(defaultSourceService, SOURCE_LIMIT);
    }

    public void enqueue(UUID rawVideoId, String pipelineVersion, String reason) {
        enqueue(rawVideoId, pipelineVersion, defaultSourceService, reason);
    }

    public void enqueue(UUID rawVideoId, String pipelineVersion, String sourceService, String reason) {
        if (rawVideoId == null) {
            return;
        }
        AggregationWorkPayload payload = new AggregationWorkPayload(
                rawVideoId,
                normalize(pipelineVersion, 256),
                normalize(StringUtils.hasText(sourceService) ? sourceService : defaultSourceService, SOURCE_LIMIT),
                normalize(reason, REASON_LIMIT));
        workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                AggregationWorkTypes.RAW_VIDEO,
                AggregationWorkTypes.taskId(rawVideoId),
                rawVideoId.toString(),
                payload.pipelineVersion(),
                serialize(payload)));
        log.debug("Enqueued aggregation runtime work: rawVideoId={}, reason={}", rawVideoId, payload.reason());
    }

    private String serialize(AggregationWorkPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize aggregation work", ex);
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
