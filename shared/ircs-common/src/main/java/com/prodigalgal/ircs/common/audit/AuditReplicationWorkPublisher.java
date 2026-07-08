package com.prodigalgal.ircs.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(RuntimeWorkQueue.class)
public class AuditReplicationWorkPublisher implements InitializingBean, DisposableBean {

    private static final String EVENT_UPSERT = "UPSERT";

    private final RuntimeWorkQueue workQueue;
    private final ObjectMapper objectMapper;

    public AuditReplicationWorkPublisher(RuntimeWorkQueue workQueue, ObjectMapper objectMapper) {
        this.workQueue = workQueue;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterPropertiesSet() {
        AuditReplicationWorkDispatcher.register(this);
    }

    @Override
    public void destroy() {
        AuditReplicationWorkDispatcher.unregister(this);
    }

    public void enqueue(AuditClass auditClass, String sourceTable, UUID sourceId) {
        enqueue(auditClass, sourceTable, sourceId, EVENT_UPSERT, null);
    }

    public void enqueue(
            AuditClass auditClass,
            String sourceTable,
            UUID sourceId,
            String eventType,
            String payload) {
        if (!StringUtils.hasText(sourceTable) || sourceId == null) {
            return;
        }
        String normalizedEventType = StringUtils.hasText(eventType) ? eventType.trim() : EVENT_UPSERT;
        AuditReplicationWorkPayload workPayload = new AuditReplicationWorkPayload(
                auditClass == null ? AuditClass.SYSTEM : auditClass,
                sourceTable.trim(),
                sourceId,
                normalizedEventType,
                payload);
        workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                AuditReplicationWorkTypes.ES_REPLICATION,
                AuditReplicationWorkTypes.taskId(workPayload.sourceTable(), sourceId),
                sourceId.toString(),
                normalizedEventType,
                serialize(workPayload)));
    }

    private String serialize(AuditReplicationWorkPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit replication work", ex);
        }
    }
}
