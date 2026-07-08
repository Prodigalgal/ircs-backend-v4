package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.dashboard.domain.DashboardStatsSnapshot;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardStatsResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class DashboardStatsSnapshotRepository {

    static final String SNAPSHOT_KEY = "statistics";
    private static final String FRESH_TTL_KEY = "app.ops.dashboard.snapshot.fresh-ttl";
    private static final String STALE_GRACE_KEY = "app.ops.dashboard.snapshot.stale-grace";
    private static final Duration DEFAULT_FRESH_TTL = Duration.ofSeconds(6);
    private static final Duration DEFAULT_STALE_GRACE = Duration.ofSeconds(60);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RuntimeConfigService runtimeConfig;

    public Optional<DashboardStatsSnapshot> findUsable() {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT raw_count_db,
                           raw_count_es,
                           unified_count_db,
                           unified_count_es,
                           total_tasks,
                           pending_normalization,
                           pending_enrichment,
                           normalization_failed,
                           enrichment_missing_douban,
                           enrichment_missing_tmdb,
                           image_download_failed,
                           image_dead_link,
                           generated_at,
                           expires_at,
                           stale_until,
                           source
                      FROM ops_dashboard_stats_snapshots
                     WHERE snapshot_key = :snapshotKey
                       AND stale_until > now()
                    """,
                    Map.of("snapshotKey", SNAPSHOT_KEY),
                    rs -> rs.next() ? Optional.of(mapSnapshot(rs)) : Optional.empty());
        } catch (DataAccessException ex) {
            log.debug("Dashboard stats snapshot read failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void save(DashboardStatsResponse stats, Instant generatedAt) {
        if (stats == null) {
            return;
        }
        Instant safeGeneratedAt = generatedAt == null ? Instant.now() : generatedAt;
        Instant expiresAt = safeGeneratedAt.plus(freshTtl());
        Instant staleUntil = expiresAt.plus(staleGrace());
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO ops_dashboard_stats_snapshots (
                        snapshot_key,
                        raw_count_db,
                        raw_count_es,
                        unified_count_db,
                        unified_count_es,
                        total_tasks,
                        pending_normalization,
                        pending_enrichment,
                        normalization_failed,
                        enrichment_missing_douban,
                        enrichment_missing_tmdb,
                        image_download_failed,
                        image_dead_link,
                        generated_at,
                        expires_at,
                        stale_until,
                        source,
                        updated_at
                    ) VALUES (
                        :snapshotKey,
                        :rawCountDb,
                        :rawCountEs,
                        :unifiedCountDb,
                        :unifiedCountEs,
                        :totalTasks,
                        :pendingNormalization,
                        :pendingEnrichment,
                        :normalizationFailed,
                        :enrichmentMissingDouban,
                        :enrichmentMissingTmdb,
                        :imageDownloadFailed,
                        :imageDeadLink,
                        :generatedAt,
                        :expiresAt,
                        :staleUntil,
                        :source,
                        now()
                    )
                    ON CONFLICT (snapshot_key) DO UPDATE SET
                        raw_count_db = EXCLUDED.raw_count_db,
                        raw_count_es = EXCLUDED.raw_count_es,
                        unified_count_db = EXCLUDED.unified_count_db,
                        unified_count_es = EXCLUDED.unified_count_es,
                        total_tasks = EXCLUDED.total_tasks,
                        pending_normalization = EXCLUDED.pending_normalization,
                        pending_enrichment = EXCLUDED.pending_enrichment,
                        normalization_failed = EXCLUDED.normalization_failed,
                        enrichment_missing_douban = EXCLUDED.enrichment_missing_douban,
                        enrichment_missing_tmdb = EXCLUDED.enrichment_missing_tmdb,
                        image_download_failed = EXCLUDED.image_download_failed,
                        image_dead_link = EXCLUDED.image_dead_link,
                        generated_at = EXCLUDED.generated_at,
                        expires_at = EXCLUDED.expires_at,
                        stale_until = EXCLUDED.stale_until,
                        source = EXCLUDED.source,
                        updated_at = now()
                    """,
                    snapshotParameters(stats, safeGeneratedAt, expiresAt, staleUntil));
        } catch (DataAccessException ex) {
            log.debug("Dashboard stats snapshot write failed: {}", ex.getMessage());
        }
    }

    public void invalidate() {
        try {
            jdbcTemplate.update(
                    """
                    UPDATE ops_dashboard_stats_snapshots
                       SET expires_at = LEAST(expires_at, now()),
                           stale_until = LEAST(stale_until, now()),
                           updated_at = now()
                     WHERE snapshot_key = :snapshotKey
                    """,
                    Map.of("snapshotKey", SNAPSHOT_KEY));
        } catch (DataAccessException ex) {
            log.debug("Dashboard stats snapshot invalidation failed: {}", ex.getMessage());
        }
    }

    private MapSqlParameterSource snapshotParameters(
            DashboardStatsResponse stats,
            Instant generatedAt,
            Instant expiresAt,
            Instant staleUntil) {
        return new MapSqlParameterSource()
                .addValue("snapshotKey", SNAPSHOT_KEY)
                .addValue("rawCountDb", stats.rawCountDb())
                .addValue("rawCountEs", stats.rawCountEs())
                .addValue("unifiedCountDb", stats.unifiedCountDb())
                .addValue("unifiedCountEs", stats.unifiedCountEs())
                .addValue("totalTasks", stats.totalTasks())
                .addValue("pendingNormalization", stats.pendingNormalization())
                .addValue("pendingEnrichment", stats.pendingEnrichment())
                .addValue("normalizationFailed", stats.normalizationFailed())
                .addValue("enrichmentMissingDouban", stats.enrichmentMissingDouban())
                .addValue("enrichmentMissingTmdb", stats.enrichmentMissingTmdb())
                .addValue("imageDownloadFailed", stats.imageDownloadFailed())
                .addValue("imageDeadLink", stats.imageDeadLink())
                .addValue("generatedAt", Timestamp.from(generatedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("staleUntil", Timestamp.from(staleUntil))
                .addValue("source", "database");
    }

    private DashboardStatsSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        DashboardStatsResponse stats = new DashboardStatsResponse(
                rs.getLong("raw_count_db"),
                rs.getLong("raw_count_es"),
                rs.getLong("unified_count_db"),
                rs.getLong("unified_count_es"),
                rs.getLong("total_tasks"),
                rs.getLong("pending_normalization"),
                rs.getLong("pending_enrichment"),
                rs.getLong("normalization_failed"),
                rs.getLong("enrichment_missing_douban"),
                rs.getLong("enrichment_missing_tmdb"),
                rs.getLong("image_download_failed"),
                rs.getLong("image_dead_link"));
        return new DashboardStatsSnapshot(
                stats,
                instant(rs, "generated_at"),
                instant(rs, "expires_at"),
                instant(rs, "stale_until"),
                rs.getString("source"));
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
