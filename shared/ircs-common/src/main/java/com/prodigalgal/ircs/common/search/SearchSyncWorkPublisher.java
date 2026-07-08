package com.prodigalgal.ircs.common.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
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
public class SearchSyncWorkPublisher {

    private static final int SOURCE_LIMIT = 128;
    private static final int CORRELATION_LIMIT = 128;

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;
    private final String defaultSourceService;

    public SearchSyncWorkPublisher(
            RuntimeWorkQueue workQueue,
            ObjectMapper objectMapper,
            @Value("${spring.application.name:ircs-service}") String defaultSourceService) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
        this.defaultSourceService = normalize(defaultSourceService, SOURCE_LIMIT);
    }

    public int enqueue(UUID entityId, SearchEntityType entityType, SyncOperation operation) {
        return enqueue(entityId, entityType, operation, defaultSourceService, null);
    }

    public int enqueue(
            UUID entityId,
            SearchEntityType entityType,
            SyncOperation operation,
            String sourceService,
            String correlationId) {
        return enqueueBatch(
                entityId == null ? java.util.List.of() : java.util.List.of(entityId),
                entityType,
                operation,
                sourceService,
                correlationId);
    }

    public int enqueueBatch(Collection<UUID> entityIds, SearchEntityType entityType, SyncOperation operation) {
        return enqueueBatch(entityIds, entityType, operation, defaultSourceService, null);
    }

    public int enqueueBatch(
            Collection<UUID> entityIds,
            SearchEntityType entityType,
            SyncOperation operation,
            String sourceService,
            String correlationId) {
        Objects.requireNonNull(entityType, "entityType is required");
        Objects.requireNonNull(operation, "operation is required");
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (entityIds != null) {
            entityIds.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return 0;
        }

        String source = normalize(
                StringUtils.hasText(sourceService) ? sourceService : defaultSourceService,
                SOURCE_LIMIT);
        String correlation = normalize(correlationId, CORRELATION_LIMIT);
        for (UUID entityId : ids) {
            SearchSyncWorkPayload payload = new SearchSyncWorkPayload(
                    entityId,
                    entityType,
                    operation,
                    source,
                    correlation);
            workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                    SearchSyncWorkTypes.taskType(entityType),
                    taskId(entityType, entityId),
                    entityId.toString(),
                    operation.name(),
                    serialize(payload)));
        }
        log.debug("Enqueued {} search sync runtime tasks for {} {}", ids.size(), entityType, operation);
        return ids.size();
    }

    public static String taskId(SearchEntityType entityType, UUID entityId) {
        if (entityType == null || entityId == null) {
            throw new IllegalArgumentException("entityType and entityId are required");
        }
        return entityType.name().toLowerCase(Locale.ROOT) + ":" + entityId;
    }

    private String serialize(SearchSyncWorkPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize search sync work", ex);
        }
    }

    private static String normalize(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
