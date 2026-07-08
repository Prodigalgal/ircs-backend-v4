package com.prodigalgal.ircs.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LlmCleaningRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void findCandidateReadsRawVideoSourceCategoryWithoutDbLease() {
        UUID rawId = UUID.randomUUID();
        when(jdbcTemplate.query(
                        argThat(sql -> sql != null
                                && sql.contains("from raw_videos")
                                && sql.contains("where id = :rawId")
                                && sql.contains("category_code")
                                && !sql.contains("skip locked")
                                && !sql.contains("llm_cleaning_claimed")),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of(new LlmCleaningCandidate(rawId, "国语")));
        LlmCleaningRepository repository = new LlmCleaningRepository(jdbcTemplate);

        Optional<LlmCleaningCandidate> candidate = repository.findCandidate(LlmCleaningKind.CATEGORY, rawId);

        assertThat(candidate).contains(new LlmCleaningCandidate(rawId, "国语"));
    }

    @Test
    void applyMatchOnlyUpdatesUnmappedRawVideoCategoryCode() {
        UUID rawId = UUID.randomUUID();
        UUID standardId = UUID.randomUUID();
        when(jdbcTemplate.update(
                        argThat(sql -> sql != null
                                && sql.contains("update raw_videos")
                                && sql.contains("select slug")
                                && sql.contains("updated_at = now()")
                                && sql.contains("category_code")
                                && !sql.contains("llm_cleaning_claimed")),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);
        LlmCleaningRepository repository = new LlmCleaningRepository(jdbcTemplate);

        boolean changed = repository.applyMatch(LlmCleaningKind.CATEGORY, rawId, standardId);

        assertThat(changed).isTrue();
    }

    @Test
    void nonCategoryKindsAreRejectedAfterFlatMetadataMigration() {
        UUID rawId = UUID.randomUUID();
        UUID standardId = UUID.randomUUID();
        LlmCleaningRepository repository = new LlmCleaningRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.findCandidate(LlmCleaningKind.LANGUAGE, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only category LLM cleaning is supported");
        assertThatThrownBy(() -> repository.applyMatch(LlmCleaningKind.GENRE, rawId, standardId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only category LLM cleaning is supported");
        assertThatThrownBy(() -> repository.applyNoise(LlmCleaningKind.AREA, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only category LLM cleaning is supported");
        verify(jdbcTemplate, never()).update(any(String.class), any(MapSqlParameterSource.class));
    }

    @Test
    void categoryNoiseDoesNotOverwriteAlreadyMappedRows() {
        UUID rawId = UUID.randomUUID();
        LlmCleaningRepository repository = new LlmCleaningRepository(jdbcTemplate);

        boolean changed = repository.applyNoise(LlmCleaningKind.CATEGORY, rawId);

        assertThat(changed).isFalse();
        verify(jdbcTemplate, never()).update(any(String.class), any(MapSqlParameterSource.class));
        verify(jdbcTemplate, never()).queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Boolean.class));
    }

    @Test
    void categoryStandardsOnlyExposeTwelveStableTopCategories() {
        when(jdbcTemplate.query(
                        argThat(sql -> sql != null
                                && sql.contains("from standard_category")
                                && sql.contains("slug in (:allowedCategoryCodes)")),
                        org.mockito.ArgumentMatchers.<Map<String, ?>>argThat(params -> params != null
                                && params.containsKey("allowedCategoryCodes")
                                && ((List<?>) params.get("allowedCategoryCodes")).containsAll(List.of(
                                        "movie",
                                        "series",
                                        "short-drama",
                                        "anime",
                                        "variety",
                                        "documentary",
                                        "sports",
                                        "news",
                                        "education",
                                        "music",
                                        "adult",
                                        "other"))),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        LlmCleaningRepository repository = new LlmCleaningRepository(jdbcTemplate);

        assertThat(repository.findStandards(LlmCleaningKind.CATEGORY)).isEmpty();
    }
}
