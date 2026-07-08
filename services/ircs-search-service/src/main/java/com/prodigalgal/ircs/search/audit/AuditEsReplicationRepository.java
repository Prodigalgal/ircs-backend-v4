package com.prodigalgal.ircs.search.audit;

import com.prodigalgal.ircs.common.audit.AuditReplicationWorkPayload;
import com.prodigalgal.ircs.search.document.AuditEventSearchDocument;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
class AuditEsReplicationRepository {

    static final String REQUEST_AUDIT_LOGS = "request_audit_logs";
    static final String WORKER_JOB_AUDIT_EVENTS = "worker_job_audit_events";
    static final String NOTIFICATION_MAIL_SEND_HISTORY = "notification_mail_send_history";

    private final JdbcTemplate jdbcTemplate;

    Optional<AuditEventSearchDocument> findDocument(AuditReplicationWorkPayload payload) {
        if (payload == null || !StringUtils.hasText(payload.sourceTable()) || payload.sourceId() == null) {
            return Optional.empty();
        }
        return switch (payload.sourceTable().trim()) {
            case REQUEST_AUDIT_LOGS -> query("""
                    select id, created_at, updated_at, audit_class, username, method, path, query_string,
                           status_code, success, duration_ms, client_ip, user_agent, trace_id, error_message
                      from request_audit_logs
                     where id = ?
                    """, payload, this::requestDocument);
            case WORKER_JOB_AUDIT_EVENTS -> query("""
                    select id, created_at, updated_at, audit_class, job_source, job_type, job_name,
                           correlation_id, status, duration_ms, error_class, error_message
                      from worker_job_audit_events
                     where id = ?
                    """, payload, this::workerDocument);
            case NOTIFICATION_MAIL_SEND_HISTORY -> query("""
                    select id, created_at, updated_at, audit_class, correlation_id, recipient, subject,
                           template_code, delivery_mode, status, credential_id, failure_code, failure_message
                      from notification_mail_send_history
                     where id = ?
                    """, payload, this::mailDocument);
            default -> Optional.empty();
        };
    }

    private Optional<AuditEventSearchDocument> query(
            String sql,
            AuditReplicationWorkPayload payload,
            DocumentMapper mapper) {
        List<AuditEventSearchDocument> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapper.map(rs, payload), payload.sourceId());
        return rows.stream().findFirst();
    }

    private AuditEventSearchDocument requestDocument(ResultSet rs, AuditReplicationWorkPayload payload) throws SQLException {
        AuditEventSearchDocument doc = baseDocument(rs, payload, "REQUEST");
        doc.setUsername(rs.getString("username"));
        doc.setMethod(rs.getString("method"));
        doc.setPath(rs.getString("path"));
        doc.setQueryString(rs.getString("query_string"));
        doc.setStatusCode(intValue(rs, "status_code"));
        doc.setSuccess(booleanValue(rs, "success"));
        doc.setDurationMs(longValue(rs, "duration_ms"));
        doc.setClientIp(rs.getString("client_ip"));
        doc.setUserAgent(rs.getString("user_agent"));
        doc.setTraceId(rs.getString("trace_id"));
        doc.setMessage(rs.getString("error_message"));
        doc.setStatus(statusFromSuccess(doc.getSuccess()));
        return doc;
    }

    private AuditEventSearchDocument workerDocument(ResultSet rs, AuditReplicationWorkPayload payload) throws SQLException {
        AuditEventSearchDocument doc = baseDocument(rs, payload, "WORKER_JOB");
        doc.setJobSource(rs.getString("job_source"));
        doc.setJobType(rs.getString("job_type"));
        doc.setJobName(rs.getString("job_name"));
        doc.setCorrelationId(rs.getString("correlation_id"));
        doc.setStatus(rs.getString("status"));
        doc.setDurationMs(longValue(rs, "duration_ms"));
        doc.setErrorClass(rs.getString("error_class"));
        doc.setMessage(rs.getString("error_message"));
        return doc;
    }

    private AuditEventSearchDocument mailDocument(ResultSet rs, AuditReplicationWorkPayload payload) throws SQLException {
        AuditEventSearchDocument doc = baseDocument(rs, payload, "MAIL");
        doc.setCorrelationId(rs.getString("correlation_id"));
        doc.setRecipient(rs.getString("recipient"));
        doc.setSubject(rs.getString("subject"));
        doc.setTemplateCode(rs.getString("template_code"));
        doc.setDeliveryMode(rs.getString("delivery_mode"));
        doc.setStatus(rs.getString("status"));
        doc.setCredentialId(stringValue(rs, "credential_id"));
        doc.setFailureCode(rs.getString("failure_code"));
        doc.setMessage(rs.getString("failure_message"));
        return doc;
    }

    private AuditEventSearchDocument baseDocument(
            ResultSet rs,
            AuditReplicationWorkPayload payload,
            String recordType) throws SQLException {
        String sourceTable = payload.sourceTable().trim();
        UUID sourceId = payload.sourceId();
        AuditEventSearchDocument doc = new AuditEventSearchDocument();
        doc.setId(sourceTable + ":" + sourceId);
        doc.setSourceTable(sourceTable);
        doc.setSourceId(sourceId.toString());
        doc.setRecordType(recordType);
        doc.setEventType(StringUtils.hasText(payload.eventType()) ? payload.eventType().trim() : "UPSERT");
        String rowAuditClass = rs.getString("audit_class");
        doc.setAuditClass(payload.auditClass() == null ? rowAuditClass : payload.auditClass().name());
        doc.setCreatedAt(instantValue(rs, "created_at"));
        doc.setUpdatedAt(instantValue(rs, "updated_at"));
        doc.setIndexedAt(Instant.now());
        return doc;
    }

    private static String statusFromSuccess(Boolean success) {
        if (success == null) {
            return null;
        }
        return success ? "SUCCESS" : "FAILED";
    }

    private static Instant instantValue(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer intValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longValue(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean booleanValue(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static String stringValue(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value.toString();
    }

    @FunctionalInterface
    private interface DocumentMapper {
        AuditEventSearchDocument map(ResultSet rs, AuditReplicationWorkPayload payload) throws SQLException;
    }
}
