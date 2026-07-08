package com.prodigalgal.ircs.ops.audit.request;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
class RequestAuditSummarySnapshotRepository {

    private static final String SNAPSHOT_KEY = "last-24h";
    private static final String FRESH_TTL_KEY = "app.ops.request-audit.summary-snapshot.fresh-ttl";
    private static final String STALE_GRACE_KEY = "app.ops.request-audit.summary-snapshot.stale-grace";
    private static final Duration DEFAULT_FRESH_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_STALE_GRACE = Duration.ofMinutes(5);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RuntimeConfigService runtimeConfig;

    Optional<RequestAuditSummarySnapshot> findUsable() {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT total_count,
                           error_count,
                           slow_count,
                           max_duration_ms,
                           generated_at,
                           expires_at,
                           stale_until,
                           source
                      FROM ops_request_audit_summary_snapshots
                     WHERE snapshot_key = :snapshotKey
                       AND stale_until > now()
                    """,
                    Map.of("snapshotKey", SNAPSHOT_KEY),
                    rs -> rs.next() ? Optional.of(mapSnapshot(rs)) : Optional.empty());
        } catch (RuntimeException ex) {
            log.debug("Request audit summary snapshot read failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    void save(RequestAuditSummaryResponse summary, Instant generatedAt) {
        if (summary == null) {
            return;
        }
        Instant safeGeneratedAt = generatedAt == null ? Instant.now() : generatedAt;
        Instant expiresAt = safeGeneratedAt.plus(freshTtl());
        Instant staleUntil = expiresAt.plus(staleGrace());
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO ops_request_audit_summary_snapshots (
                        snapshot_key,
                        total_count,
                        error_count,
                        slow_count,
                        max_duration_ms,
                        generated_at,
                        expires_at,
                        stale_until,
                        source,
                        updated_at
                    ) VALUES (
                        :snapshotKey,
                        :totalCount,
                        :errorCount,
                        :slowCount,
                        :maxDurationMs,
                        :generatedAt,
                        :expiresAt,
                        :staleUntil,
                        :source,
                        now()
                    )
                    ON CONFLICT (snapshot_key) DO UPDATE SET
                        total_count = EXCLUDED.total_count,
                        error_count = EXCLUDED.error_count,
                        slow_count = EXCLUDED.slow_count,
                        max_duration_ms = EXCLUDED.max_duration_ms,
                        generated_at = EXCLUDED.generated_at,
                        expires_at = EXCLUDED.expires_at,
                        stale_until = EXCLUDED.stale_until,
                        source = EXCLUDED.source,
                        updated_at = now()
                    """,
                    snapshotParameters(summary, safeGeneratedAt, expiresAt, staleUntil));
        } catch (RuntimeException ex) {
            log.debug("Request audit summary snapshot write failed: {}", ex.getMessage());
        }
    }

    private MapSqlParameterSource snapshotParameters(
            RequestAuditSummaryResponse summary,
            Instant generatedAt,
            Instant expiresAt,
            Instant staleUntil) {
        return new MapSqlParameterSource()
                .addValue("snapshotKey", SNAPSHOT_KEY)
                .addValue("totalCount", summary.totalLast24h())
                .addValue("errorCount", summary.errorsLast24h())
                .addValue("slowCount", summary.slowLast24h())
                .addValue("maxDurationMs", summary.maxDurationMsLast24h())
                .addValue("generatedAt", Timestamp.from(generatedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("staleUntil", Timestamp.from(staleUntil))
                .addValue("source", "database");
    }

    private RequestAuditSummarySnapshot mapSnapshot(ResultSet rs) throws SQLException {
        RequestAuditSummaryResponse summary = new RequestAuditSummaryResponse(
                rs.getLong("total_count"),
                rs.getLong("error_count"),
                rs.getLong("slow_count"),
                getLong(rs, "max_duration_ms"));
        return new RequestAuditSummarySnapshot(
                summary,
                instant(rs, "generated_at"),
                instant(rs, "expires_at"),
                instant(rs, "stale_until"),
                rs.getString("source"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }

    private Duration freshTtl() {
        return runtimeConfig == null
                ? DEFAULT_FRESH_TTL
                : runtimeConfig.positiveDurationValue(FRESH_TTL_KEY, DEFAULT_FRESH_TTL);
    }

    private Duration staleGrace() {
        return runtimeConfig == null
                ? DEFAULT_STALE_GRACE
                : runtimeConfig.positiveDurationValue(STALE_GRACE_KEY, DEFAULT_STALE_GRACE);
    }
}
