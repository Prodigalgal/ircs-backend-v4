package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class RawVideoNormalizationRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void markReadyWritesFlatMetadataWithoutLegacyRelationTables() {
        UUID rawVideoId = UUID.randomUUID();
        doReturn(List.of("action"))
                .when(jdbcTemplate)
                .queryForList(argThat(sql -> sql.contains("standard_genre sg")), any(MapSqlParameterSource.class), eq(String.class));
        doReturn(List.of("zh-CN"))
                .when(jdbcTemplate)
                .queryForList(argThat(sql -> sql.contains("standard_languages sl")), any(MapSqlParameterSource.class), eq(String.class));
        doReturn(List.of("CN"))
                .when(jdbcTemplate)
                .queryForList(argThat(sql -> sql.contains("standard_areas sa")), any(MapSqlParameterSource.class), eq(String.class));

        RawVideoNormalizationRepository repository = RawVideoNormalizationRepository.forTest(jdbcTemplate);

        RawVideoNormalizationRepository.MarkReadyResult result = repository.markReady(rawVideoId, patch(
                Set.of("动作"),
                Set.of("国语"),
                Set.of("中国大陆"),
                Set.of("演员甲"),
                Set.of("导演甲")));

        assertEquals(new RawVideoNormalizationRepository.MarkReadyResult(), result);
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null
                        && sql.contains("actor_names")
                        && sql.contains("director_names")
                        && sql.contains("area_codes")
                        && sql.contains("language_codes")
                        && sql.contains("genre_codes")
                        && sql.contains("normalization_snapshot")),
                any(MapSqlParameterSource.class));
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(SqlParameterSource[].class));
    }

    @Test
    void markReadyDoesNotResolveCodesWhenPatchHasNoRelationValues() {
        UUID rawVideoId = UUID.randomUUID();
        RawVideoNormalizationRepository repository = RawVideoNormalizationRepository.forTest(jdbcTemplate);

        repository.markReady(rawVideoId, patch(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));

        verify(jdbcTemplate, never()).queryForList(
                argThat(sql -> sql != null && sql.contains("standard_")),
                any(MapSqlParameterSource.class),
                eq(String.class));
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(SqlParameterSource[].class));
    }

    @Test
    void markReadyWritesSourceCategoryIntoRawVideoWithoutLegacyMappingTable() {
        UUID rawVideoId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                        argThat(sql -> sql.contains("select slug")
                                && sql.contains("from standard_category")
                                && sql.contains("cast(:fallbackCategoryCode as text)")),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("movie");

        RawVideoNormalizationRepository repository = RawVideoNormalizationRepository.forTest(jdbcTemplate);

        repository.markReady(rawVideoId, patchWithCategory(dataSourceId, "movie", "电影"));

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("select slug")
                        && sql.contains("standard_category")
                        && sql.contains("cast(:fallbackCategoryCode as text)")),
                any(MapSqlParameterSource.class),
                eq(String.class));
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("update raw_videos")
                        && sql.contains("data_source_id = coalesce(data_source_id, :dataSourceId)")
                        && sql.contains("source_category_code")
                        && sql.contains("source_category_name")
                        && sql.contains("category_code")),
                any(MapSqlParameterSource.class));
    }

    @Test
    void sampleRawVideoIdsOrdersByOldestUpdatedAt() {
        UUID rawVideoId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                        argThat(sql -> sql.contains("from raw_videos")
                                && sql.contains("order by updated_at asc nulls first, id asc")
                                && sql.contains("limit :limit")),
                        any(MapSqlParameterSource.class),
                        eq(UUID.class)))
                .thenReturn(List.of(rawVideoId));

        RawVideoNormalizationRepository repository = RawVideoNormalizationRepository.forTest(jdbcTemplate);

        assertEquals(List.of(rawVideoId), repository.sampleRawVideoIds(5));
    }

    @Test
    void resetAllNormalizationPendingClearsRetryStateForHistoricalRemap() {
        when(jdbcTemplate.update(
                        argThat(sql -> sql.contains("update raw_videos")
                                && sql.contains("normalization_status = 'PENDING'")
                                && sql.contains("updated_at = now()")
                                && sql.contains("next_normalization_retry_time = null")
                                && sql.contains("normalization_retry_count = 0")),
                        any(MapSqlParameterSource.class)))
                .thenReturn(12);

        RawVideoNormalizationRepository repository = RawVideoNormalizationRepository.forTest(jdbcTemplate);

        assertEquals(12, repository.resetAllNormalizationPending());
    }

    @Test
    void countRawVideosReturnsZeroWhenJdbcReturnsNull() {
        when(jdbcTemplate.queryForObject(
                        contains("select count(*) from raw_videos"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(null);

        RawVideoNormalizationRepository repository = RawVideoNormalizationRepository.forTest(jdbcTemplate);

        assertEquals(0L, repository.countRawVideos());
    }

    private RawVideoPatch patch(
            Set<String> genres,
            Set<String> languages,
            Set<String> areas,
            Set<String> actors,
            Set<String> directors) {
        return patch(null, null, null, genres, languages, areas, actors, directors);
    }

    private RawVideoPatch patchWithCategory(UUID dataSourceId, String sourceCode, String sourceName) {
        return patch(dataSourceId, sourceCode, sourceName, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private RawVideoPatch patch(
            UUID dataSourceId,
            String sourceCode,
            String sourceName,
            Set<String> genres,
            Set<String> languages,
            Set<String> areas,
            Set<String> actors,
            Set<String> directors) {
        return new RawVideoPatch(
                "Title",
                null,
                null,
                null,
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                dataSourceId,
                sourceCode,
                sourceName,
                genres,
                languages,
                areas,
                actors,
                directors);
    }
}
