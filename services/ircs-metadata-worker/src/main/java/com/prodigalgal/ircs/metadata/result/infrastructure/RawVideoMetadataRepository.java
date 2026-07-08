package com.prodigalgal.ircs.metadata.result.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.metadata.result.domain.RawVideoMetadataRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RawVideoMetadataRepository {

    public static final String SELECT_BY_ID = """
            select id, douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                   description, score, year, alias_title, raw_metadata::text, locked_fields,
                   enrichment_status, enrichment_retry_count, aggregation_status, data_hash
            from raw_videos
            where id = ?
            """;

    public static final String UPDATE_METADATA = """
            update raw_videos
            set douban_id = ?,
                tmdb_id = ?,
                imdb_id = ?,
                rotten_tomatoes_id = ?,
                description = ?,
                score = ?,
                year = ?,
                alias_title = ?,
                raw_metadata = cast(? as jsonb),
                enrichment_status = ?,
                enrichment_retry_count = ?,
                aggregation_status = ?,
                updated_at = now()
            where id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<RawVideoMetadataRecord> findById(UUID id) {
        return jdbcTemplate.query(SELECT_BY_ID, ps -> ps.setObject(1, id), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapRow(rs));
        });
    }

    public int save(RawVideoMetadataRecord record) {
        return jdbcTemplate.update(
                UPDATE_METADATA,
                record.getDoubanId(),
                record.getTmdbId(),
                record.getImdbId(),
                record.getRottenTomatoesId(),
                record.getDescription(),
                record.getScore(),
                record.getYear(),
                record.getAliasTitle(),
                record.getRawMetadata(),
                record.getEnrichmentStatus(),
                record.getEnrichmentRetryCount(),
                record.getAggregationStatus(),
                record.getId());
    }

    private RawVideoMetadataRecord mapRow(ResultSet rs) throws SQLException {
        return new RawVideoMetadataRecord(
                rs.getObject("id", UUID.class),
                rs.getString("douban_id"),
                rs.getString("tmdb_id"),
                rs.getString("imdb_id"),
                rs.getString("rotten_tomatoes_id"),
                rs.getString("description"),
                rs.getBigDecimal("score"),
                rs.getString("year"),
                rs.getString("alias_title"),
                rs.getString("raw_metadata"),
                readLockedFields(rs.getString("locked_fields")),
                rs.getString("enrichment_status"),
                (Integer) rs.getObject("enrichment_retry_count"),
                rs.getString("aggregation_status"),
                rs.getString("data_hash"));
    }

    private Set<String> readLockedFields(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptySet();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }
}
