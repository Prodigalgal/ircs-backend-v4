package com.prodigalgal.ircs.scraper;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class RawVideoRefetchRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    Optional<RawVideoRefetchTarget> findTarget(UUID rawVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select rv.id, rv.source_vid, rv.data_source_id as data_source_id
                    from raw_videos rv
                    where rv.id = :id
                    """,
                    new MapSqlParameterSource("id", rawVideoId),
                    (rs, rowNum) -> new RawVideoRefetchTarget(
                            rs.getObject("id", UUID.class),
                            rs.getString("source_vid"),
                            rs.getObject("data_source_id", UUID.class))));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    record RawVideoRefetchTarget(UUID id, String sourceVid, UUID dataSourceId) {
    }
}
