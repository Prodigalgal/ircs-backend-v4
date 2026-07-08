package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.dashboard.domain.DashboardReadModelSnapshot;
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
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
@Slf4j
public class DashboardReadModelSnapshotRepository {

    public static final String DISTRIBUTIONS_KEY = "distributions";
    public static final String SOURCE_QUALITY_KEY = "source-quality";

    private static final String FRESH_TTL_KEY = "app.ops.dashboard.read-model-snapshot.fresh-ttl";
    private static final String STALE_GRACE_KEY = "app.ops.dashboard.read-model-snapshot.stale-grace";
    private static final Duration DEFAULT_FRESH_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_STALE_GRACE = Duration.ofMinutes(5);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RuntimeConfigService runtimeConfig;

    public <T> Optional<DashboardReadModelSnapshot<T>> findUsable(String key, JavaType payloadType) {
        if (!StringUtils.hasText(key) || payloadType == null) {
            return Optional.empty();
        }
        try {
            return jdbcTemplate.query(
                    """
                    SELECT payload::text AS payload,
                           generated_at,
                           expires_at,
                           stale_until,
                           source
                      FROM ops_dashboard_read_model_snapshots
                     WHERE snapshot_key = :snapshotKey
                       AND stale_until > now()
                    """,
                    Map.of("snapshotKey", key.trim()),
                    rs -> rs.next() ? Optional.of(mapSnapshot(rs, payloadType)) : Optional.empty());
        } catch (RuntimeException ex) {
            log.debug("Dashboard read-model snapshot read failed: key={}, error={}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    public void save(String key, Object payload, Instant generatedAt) {
        if (!StringUtils.hasText(key) || payload == null) {
            return;
        }
        Instant safeGeneratedAt = generatedAt == null ? Instant.now() : generatedAt;
        Instant expiresAt = safeGeneratedAt.plus(freshTtl());
        Instant staleUntil = expiresAt.plus(staleGrace());
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO ops_dashboard_read_model_snapshots (
                        snapshot_key,
                        payload,
                        generated_at,
                        expires_at,
                        stale_until,
                        source,
                        updated_at
                    ) VALUES (
                        :snapshotKey,
                        cast(:payload as jsonb),
                        :generatedAt,
                        :expiresAt,
                        :staleUntil,
                        :source,
                        now()
                    )
                    ON CONFLICT (snapshot_key) DO UPDATE SET
                        payload = EXCLUDED.payload,
                        generated_at = EXCLUDED.generated_at,
                        expires_at = EXCLUDED.expires_at,
                        stale_until = EXCLUDED.stale_until,
                        source = EXCLUDED.source,
                        updated_at = now()
                    """,
                    snapshotParameters(key.trim(), payload, safeGeneratedAt, expiresAt, staleUntil));
        } catch (RuntimeException ex) {
            log.debug("Dashboard read-model snapshot write failed: key={}, error={}", key, ex.getMessage());
        }
    }

    public void invalidate(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    UPDATE ops_dashboard_read_model_snapshots
                       SET expires_at = LEAST(expires_at, now()),
                           stale_until = LEAST(stale_until, now()),
                           updated_at = now()
                     WHERE snapshot_key = :snapshotKey
                    """,
                    Map.of("snapshotKey", key.trim()));
        } catch (RuntimeException ex) {
            log.debug("Dashboard read-model snapshot invalidation failed: key={}, error={}", key, ex.getMessage());
        }
    }

    private MapSqlParameterSource snapshotParameters(
            String key,
            Object payload,
            Instant generatedAt,
            Instant expiresAt,
            Instant staleUntil) {
        return new MapSqlParameterSource()
                .addValue("snapshotKey", key)
                .addValue("payload", writePayload(payload))
                .addValue("generatedAt", Timestamp.from(generatedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("staleUntil", Timestamp.from(staleUntil))
                .addValue("source", "database");
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize dashboard read-model snapshot", ex);
        }
    }

    private <T> DashboardReadModelSnapshot<T> mapSnapshot(ResultSet rs, JavaType payloadType) throws SQLException {
        return new DashboardReadModelSnapshot<>(
                readPayload(rs.getString("payload"), payloadType),
                instant(rs, "generated_at"),
                instant(rs, "expires_at"),
                instant(rs, "stale_until"),
                rs.getString("source"));
    }

    private <T> T readPayload(String payload, JavaType payloadType) {
        try {
            return objectMapper.readValue(payload, payloadType);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize dashboard read-model snapshot", ex);
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
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
