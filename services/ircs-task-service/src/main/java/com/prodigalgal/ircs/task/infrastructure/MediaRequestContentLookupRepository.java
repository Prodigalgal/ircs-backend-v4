package com.prodigalgal.ircs.task.infrastructure;

import com.prodigalgal.ircs.task.domain.MediaRequestExistingVideo;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MediaRequestContentLookupRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<MediaRequestExistingVideo> findExistingVideo(String title, Integer releaseYear) {
        String normalizedTitle = normalizeTitle(title);
        if (!StringUtils.hasText(normalizedTitle)) {
            return Optional.empty();
        }
        return findUnifiedVideo(normalizedTitle, releaseYear)
                .or(() -> findRawVideo(normalizedTitle, releaseYear));
    }

    private Optional<MediaRequestExistingVideo> findUnifiedVideo(String title, Integer releaseYear) {
        return find("""
                select id
                  from unified_videos
                 where lower(btrim(title)) = ?
                   and (? is null or year = ?)
                 order by updated_at desc nulls last, id
                 limit 1
                """, "UNIFIED", title, releaseYear);
    }

    private Optional<MediaRequestExistingVideo> findRawVideo(String title, Integer releaseYear) {
        return find("""
                select id
                  from raw_videos
                 where lower(btrim(title)) = ?
                   and (? is null or year = ?)
                 order by updated_at desc nulls last, id
                 limit 1
                """, "RAW", title, releaseYear);
    }

    private Optional<MediaRequestExistingVideo> find(
            String sql,
            String source,
            String title,
            Integer releaseYear) {
        String year = releaseYear == null || releaseYear <= 0 ? null : releaseYear.toString();
        try {
            UUID id = jdbcTemplate.queryForObject(sql, UUID.class, title, year, year);
            return id == null ? Optional.empty() : Optional.of(new MediaRequestExistingVideo(id, source));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return title.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
