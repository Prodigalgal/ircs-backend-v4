package com.prodigalgal.ircs.ops.audit.worker;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.infrastructure.JdbcPageSorts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class WorkerJobAuditRepository {

    private static final int DEFAULT_SUMMARY_SAMPLE_LIMIT = 50_000;
    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("createdAt", "created_at"),
            Map.entry("updatedAt", "updated_at"),
            Map.entry("auditClass", "audit_class"),
            Map.entry("jobSource", "job_source"),
            Map.entry("jobType", "job_type"),
            Map.entry("jobName", "job_name"),
            Map.entry("correlationId", "correlation_id"),
            Map.entry("status", "status"),
            Map.entry("durationMs", "duration_ms"),
            Map.entry("errorClass", "error_class"));
    private static final String DEFAULT_ORDER = " order by created_at desc, id desc";
    private static final RowMapper<WorkerJobAuditEventResponse> ROW_MAPPER = WorkerJobAuditRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RuntimeConfigService runtimeConfig;

    WorkerJobAuditRepository(NamedParameterJdbcTemplate jdbcTemplate, RuntimeConfigService runtimeConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeConfig = runtimeConfig;
    }

    public Page<WorkerJobAuditEventResponse> findAll(
            Pageable pageable,
            String jobSource,
            String jobType,
            String jobName,
            String correlationId,
            String status,
            String errorClass,
            Instant from,
            Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();

        if (StringUtils.hasText(jobSource)) {
            predicates.add("lower(job_source) like :jobSource");
            params.addValue("jobSource", "%" + jobSource.toLowerCase() + "%");
        }
        if (StringUtils.hasText(jobType)) {
            predicates.add("lower(job_type) = :jobType");
            params.addValue("jobType", jobType.toLowerCase());
        }
        if (StringUtils.hasText(jobName)) {
            predicates.add("lower(job_name) like :jobName");
            params.addValue("jobName", "%" + jobName.toLowerCase() + "%");
        }
        if (StringUtils.hasText(correlationId)) {
            predicates.add("lower(correlation_id) like :correlationId");
            params.addValue("correlationId", "%" + correlationId.toLowerCase() + "%");
        }
        if (StringUtils.hasText(status)) {
            predicates.add("lower(status) = :status");
            params.addValue("status", status.toLowerCase());
        }
        if (StringUtils.hasText(errorClass)) {
            predicates.add("lower(error_class) like :errorClass");
            params.addValue("errorClass", "%" + errorClass.toLowerCase() + "%");
        }
        if (from != null) {
            predicates.add("created_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            predicates.add("created_at <= :to");
            params.addValue("to", Timestamp.from(to));
        }

        boolean filtered = !predicates.isEmpty();
        String where = filtered ? " where " + String.join(" and ", predicates) : "";
        String sql = """
                select id, created_at, updated_at, version, audit_class, job_source, job_type, job_name,
                       correlation_id, status, duration_ms, error_class, error_message
                  from worker_job_audit_events
                """ + where + JdbcPageSorts.orderBy(pageable, SORT_COLUMNS, DEFAULT_ORDER)
                + " limit :limit offset :offset";
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<WorkerJobAuditEventResponse> content = jdbcTemplate.query(sql, params, ROW_MAPPER);
        long total = filtered
                ? count("select count(*) from worker_job_audit_events" + where, params)
                : estimatedTotal(pageable, content.size());
        return new PageImpl<>(content, pageable, total);
    }

    public WorkerJobAuditSummaryResponse summarize(Instant since) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("since", Timestamp.from(since))
                .addValue("sampleLimit", summarySampleLimit());
        return jdbcTemplate.queryForObject(
                """
                        select count(*) as total_count,
                               coalesce(sum(case when lower(status) = 'failed' then 1 else 0 end), 0) as failed_count,
                               coalesce(sum(case when lower(status) = 'succeeded' then 1 else 0 end), 0) as succeeded_count,
                               max(duration_ms) as max_duration_ms
                          from (
                                select status, duration_ms
                                  from worker_job_audit_events
                                 where created_at >= :since
                                 order by created_at desc
                                 limit :sampleLimit
                               ) recent
                        """,
                params,
                (rs, rowNum) -> new WorkerJobAuditSummaryResponse(
                        rs.getLong("total_count"),
                        rs.getLong("failed_count"),
                        rs.getLong("succeeded_count"),
                        getLong(rs, "max_duration_ms")));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private int summarySampleLimit() {
        if (runtimeConfig == null) {
            return DEFAULT_SUMMARY_SAMPLE_LIMIT;
        }
        return runtimeConfig.boundedIntValue(
                "app.ops.worker-job-audit.summary-sample-limit",
                DEFAULT_SUMMARY_SAMPLE_LIMIT,
                1,
                1_000_000);
    }

    private static long estimatedTotal(Pageable pageable, int contentSize) {
        long floor = pageable.getOffset() + contentSize;
        return contentSize >= pageable.getPageSize() ? floor + 1 : floor;
    }

    private static WorkerJobAuditEventResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkerJobAuditEventResponse(
                getUuid(rs, "id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                getLong(rs, "version"),
                rs.getString("audit_class"),
                rs.getString("job_source"),
                rs.getString("job_type"),
                rs.getString("job_name"),
                rs.getString("correlation_id"),
                rs.getString("status"),
                getLong(rs, "duration_ms"),
                rs.getString("error_class"),
                rs.getString("error_message"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }

    private static UUID getUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
