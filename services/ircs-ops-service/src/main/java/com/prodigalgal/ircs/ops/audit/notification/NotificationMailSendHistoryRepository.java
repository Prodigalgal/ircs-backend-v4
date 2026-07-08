package com.prodigalgal.ircs.ops.audit.notification;

import com.prodigalgal.ircs.ops.infrastructure.JdbcPageSorts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
public class NotificationMailSendHistoryRepository {

    static final String SKIPPED_SEMANTICS = "SKIPPED 表示邮件发送被配置或门禁明确跳过。";
    static final String SENT_SEMANTICS = "SENT 表示 notification-worker delivery 层已接受发送意图，不代表真实 SMTP 最终投递成功。";
    static final String FAILED_SEMANTICS = "FAILED 表示 credential lease、渲染或 delivery 层失败。";

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("createdAt", "created_at"),
            Map.entry("updatedAt", "updated_at"),
            Map.entry("auditClass", "audit_class"),
            Map.entry("correlationId", "correlation_id"),
            Map.entry("recipient", "recipient"),
            Map.entry("subject", "subject"),
            Map.entry("templateCode", "template_code"),
            Map.entry("deliveryMode", "delivery_mode"),
            Map.entry("status", "status"),
            Map.entry("credentialId", "credential_id"),
            Map.entry("failureCode", "failure_code"));
    private static final String DEFAULT_ORDER = " order by created_at desc, id desc";
    private static final RowMapper<NotificationMailSendHistoryResponse> ROW_MAPPER =
            NotificationMailSendHistoryRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<NotificationMailSendHistoryResponse> findAll(
            Pageable pageable,
            String status,
            String deliveryMode,
            String templateCode,
            String correlationId,
            String recipient,
            Instant from,
            Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();

        if (StringUtils.hasText(status)) {
            predicates.add("lower(status) = :status");
            params.addValue("status", status.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(deliveryMode)) {
            predicates.add("lower(delivery_mode) = :deliveryMode");
            params.addValue("deliveryMode", deliveryMode.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(templateCode)) {
            predicates.add("lower(template_code) like :templateCode");
            params.addValue("templateCode", "%" + templateCode.toLowerCase(Locale.ROOT) + "%");
        }
        if (StringUtils.hasText(correlationId)) {
            predicates.add("lower(correlation_id) like :correlationId");
            params.addValue("correlationId", "%" + correlationId.toLowerCase(Locale.ROOT) + "%");
        }
        if (StringUtils.hasText(recipient)) {
            predicates.add("lower(recipient) like :recipient");
            params.addValue("recipient", "%" + recipient.toLowerCase(Locale.ROOT) + "%");
        }
        if (from != null) {
            predicates.add("created_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            predicates.add("created_at <= :to");
            params.addValue("to", Timestamp.from(to));
        }

        String where = predicates.isEmpty() ? "" : " where " + String.join(" and ", predicates);
        String sql = """
                select id, created_at, updated_at, version, audit_class, correlation_id, recipient, subject,
                       template_code, delivery_mode, status, credential_id, failure_code, failure_message
                  from notification_mail_send_history
                """ + where + JdbcPageSorts.orderBy(pageable, SORT_COLUMNS, DEFAULT_ORDER)
                + " limit :limit offset :offset";
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<NotificationMailSendHistoryResponse> content = jdbcTemplate.query(sql, params, ROW_MAPPER);
        long total = predicates.isEmpty()
                ? estimatedTotal(pageable, content.size())
                : count("select count(*) from notification_mail_send_history" + where, params);
        return new PageImpl<>(content, pageable, total);
    }

    public NotificationMailSendHistorySummaryResponse summarize(Instant since) {
        MapSqlParameterSource params = new MapSqlParameterSource("since", Timestamp.from(since));
        return jdbcTemplate.queryForObject(
                """
                select count(*) as total_count,
                       count(*) filter (where lower(status) = 'skipped') as skipped_count,
                       count(*) filter (where lower(status) = 'sent') as sent_count,
                       count(*) filter (where lower(status) = 'failed') as failed_count
                  from notification_mail_send_history
                 where created_at >= :since
                """,
                params,
                (rs, rowNum) -> new NotificationMailSendHistorySummaryResponse(
                        rs.getLong("total_count"),
                        rs.getLong("skipped_count"),
                        rs.getLong("sent_count"),
                        rs.getLong("failed_count"),
                        SKIPPED_SEMANTICS,
                        SENT_SEMANTICS,
                        FAILED_SEMANTICS));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private long estimatedTotal(Pageable pageable, int contentSize) {
        long floor = pageable.getOffset() + contentSize;
        return contentSize >= pageable.getPageSize() ? floor + 1 : floor;
    }

    private static NotificationMailSendHistoryResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationMailSendHistoryResponse(
                getUuid(rs, "id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                getLong(rs, "version"),
                rs.getString("audit_class"),
                rs.getString("correlation_id"),
                rs.getString("recipient"),
                rs.getString("subject"),
                rs.getString("template_code"),
                upper(rs.getString("delivery_mode")),
                upper(rs.getString("status")),
                getUuid(rs, "credential_id"),
                rs.getString("failure_code"),
                rs.getString("failure_message"));
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

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
