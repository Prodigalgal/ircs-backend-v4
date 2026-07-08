package com.prodigalgal.ircs.common.audit;

import java.util.UUID;
import org.slf4j.Logger;

public final class AuditReplicationWorkDispatcher {

    private static final String SOURCE_TABLE_REQUEST = "request_audit_logs";
    private static final String SOURCE_TABLE_WORKER = "worker_job_audit_events";
    private static final String SOURCE_TABLE_MAIL = "notification_mail_send_history";
    private static final String EVENT_UPSERT = "UPSERT";

    private static volatile AuditReplicationWorkPublisher publisher;

    private AuditReplicationWorkDispatcher() {
    }

    static void register(AuditReplicationWorkPublisher candidate) {
        publisher = candidate;
    }

    static void unregister(AuditReplicationWorkPublisher candidate) {
        if (publisher == candidate) {
            publisher = null;
        }
    }

    static void clearForTests() {
        publisher = null;
    }

    public static void enqueueRequest(AuditClass auditClass, UUID sourceId, Logger log) {
        enqueue(auditClass, SOURCE_TABLE_REQUEST, sourceId, EVENT_UPSERT, null, log);
    }

    public static void enqueueWorkerJob(AuditClass auditClass, UUID sourceId, Logger log) {
        enqueue(auditClass, SOURCE_TABLE_WORKER, sourceId, EVENT_UPSERT, null, log);
    }

    public static void enqueueMailSendHistory(AuditClass auditClass, UUID sourceId, Logger log) {
        enqueue(auditClass, SOURCE_TABLE_MAIL, sourceId, EVENT_UPSERT, null, log);
    }

    public static void enqueue(
            AuditClass auditClass,
            String sourceTable,
            UUID sourceId,
            String eventType,
            String payload,
            Logger log) {
        AuditReplicationWorkPublisher current = publisher;
        if (current == null || sourceId == null) {
            return;
        }
        try {
            current.enqueue(auditClass, sourceTable, sourceId, eventType, payload);
        } catch (RuntimeException ex) {
            if (log != null && log.isDebugEnabled()) {
                log.debug(
                        "Audit replication work enqueue skipped for {}:{}: {}",
                        sourceTable,
                        sourceId,
                        ex.getMessage());
            }
        }
    }
}
