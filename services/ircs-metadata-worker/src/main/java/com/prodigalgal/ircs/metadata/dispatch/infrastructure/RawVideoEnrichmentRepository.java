package com.prodigalgal.ircs.metadata.dispatch.infrastructure;

import com.prodigalgal.ircs.metadata.dispatch.domain.RawVideoEnrichmentRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RawVideoEnrichmentRepository {

    public static final String SELECT_BY_ID = """
            select v.id, v.title, v.alias_title, v.subtitle, v.season, v.year,
                   c.slug as category_slug,
                   v.douban_id, v.tmdb_id, v.imdb_id, v.rotten_tomatoes_id,
                   v.enrichment_status, v.enrichment_retry_count, v.data_hash
            from raw_videos v
            left join standard_category c on c.slug = v.category_code
            where v.id = ?
            """;

    private static final String UPDATE_STATUS = """
            update raw_videos
            set enrichment_status = ?,
                enrichment_retry_count = ?,
                updated_at = now()
            where id = ?
            """;

    public static final int MAX_ENRICHMENT_RETRIES = 5;

    private final JdbcTemplate jdbcTemplate;

    public Optional<RawVideoEnrichmentRecord> findById(UUID id) {
        return jdbcTemplate.query(SELECT_BY_ID, ps -> ps.setObject(1, id), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapRow(rs));
        });
    }

    public int updateStatus(UUID id, String status, Integer retryCount) {
        return jdbcTemplate.update(UPDATE_STATUS, status, retryCount, id);
    }

    private RawVideoEnrichmentRecord mapRow(ResultSet rs) throws SQLException {
        return new RawVideoEnrichmentRecord(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("alias_title"),
                rs.getString("subtitle"),
                (Integer) rs.getObject("season"),
                rs.getString("year"),
                rs.getString("category_slug"),
                rs.getString("douban_id"),
                rs.getString("tmdb_id"),
                rs.getString("imdb_id"),
                rs.getString("rotten_tomatoes_id"),
                rs.getString("enrichment_status"),
                (Integer) rs.getObject("enrichment_retry_count"),
                rs.getString("data_hash"));
    }
}
