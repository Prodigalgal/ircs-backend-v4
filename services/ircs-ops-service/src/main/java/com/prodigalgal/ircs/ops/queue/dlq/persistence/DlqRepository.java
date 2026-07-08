package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import com.prodigalgal.ircs.ops.infrastructure.JdbcPageSorts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class DlqRepository {

    private static final Set<String> STATUSES = Set.of("PENDING", "RETRIED", "DISCARDED");
    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("createdAt", "created_at"),
            Map.entry("updatedAt", "updated_at"),
            Map.entry("queueName", "queue_name"),
            Map.entry("routingKey", "routing_key"),
            Map.entry("exchange", "exchange"),
            Map.entry("retryCount", "retry_count"),
            Map.entry("status", "status"));
    private static final String DEFAULT_ORDER = " order by created_at desc, id desc";
    private static final RowMapper<FailedMessageResponse> ROW_MAPPER = DlqRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<FailedMessageResponse> findAll(Pageable pageable, String status, String queueName, String keyword) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            String normalized = status.trim().toUpperCase();
            if (!STATUSES.contains(normalized)) {
                throw new IllegalArgumentException("Unsupported DLQ status: " + status);
            }
            predicates.add("status = :status");
            params.addValue("status", normalized);
        }
        if (StringUtils.hasText(queueName)) {
            predicates.add("queue_name like :queueName");
            params.addValue("queueName", "%" + queueName + "%");
        }
        if (StringUtils.hasText(keyword)) {
            predicates.add("""
                    (
                        queue_name like :keyword
                        or routing_key like :keyword
                        or exchange like :keyword
                        or payload like :keyword
                        or exception_stack like :keyword
                    )
                    """);
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }

        boolean filtered = !predicates.isEmpty();
        String where = filtered ? " where " + String.join(" and ", predicates) : "";
        String sql = """
                select id, created_at, updated_at, version, queue_name, routing_key,
                       exchange, payload, exception_stack, retry_count, status
                  from failed_messages
                """ + where + JdbcPageSorts.orderBy(pageable, SORT_COLUMNS, DEFAULT_ORDER)
                + " limit :limit offset :offset";
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<FailedMessageResponse> content = jdbcTemplate.query(sql, params, ROW_MAPPER);
        long total = filtered
                ? count("select count(*) from failed_messages" + where, params)
                : estimatedTotal(pageable, content.size());
        return new PageImpl<>(content, pageable, total);
    }

    public Optional<FailedMessageResponse> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, created_at, updated_at, version, queue_name, routing_key,
                           exchange, payload, exception_stack, retry_count, status
                      from failed_messages
                     where id = :id
                    """,
                    Map.of("id", id),
                    ROW_MAPPER));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public int markRetried(UUID id) {
        return jdbcTemplate.update(
                """
                update failed_messages
                   set status = 'RETRIED',
                       retry_count = coalesce(retry_count, 0) + 1,
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = :id
                   and status = 'PENDING'
                """,
                Map.of("id", id));
    }

    public int markDiscarded(UUID id) {
        return jdbcTemplate.update(
                """
                update failed_messages
                   set status = 'DISCARDED',
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = :id
                   and status = 'PENDING'
                """,
                Map.of("id", id));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private static long estimatedTotal(Pageable pageable, int contentSize) {
        long floor = pageable.getOffset() + contentSize;
        return contentSize >= pageable.getPageSize() ? floor + 1 : floor;
    }

    private static FailedMessageResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FailedMessageResponse(
                rs.getObject("id", UUID.class),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                getLong(rs, "version"),
                rs.getString("queue_name"),
                rs.getString("routing_key"),
                rs.getString("exchange"),
                rs.getString("payload"),
                rs.getString("exception_stack"),
                getInteger(rs, "retry_count"),
                rs.getString("status"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.intValue();
    }
}
