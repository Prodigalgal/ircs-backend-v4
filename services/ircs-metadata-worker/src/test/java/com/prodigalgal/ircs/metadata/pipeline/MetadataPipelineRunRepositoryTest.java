package com.prodigalgal.ircs.metadata.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.pipeline.MetadataPipelineRunRepository.ProviderCompletion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MetadataPipelineRunRepositoryTest {

    @Test
    void prepareDispatchCreatesDurableRunAndReturnsPendingProviders() {
        NamedParameterJdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                        argThat(sql -> sql.contains("raw_video_enrichment_provider_runs")
                                && sql.contains("status = 'PENDING'")),
                        any(MapSqlParameterSource.class),
                        org.mockito.Mockito.eq(String.class)))
                .thenReturn(List.of("TMDB"));
        MetadataPipelineRunRepository repository = new MetadataPipelineRunRepository(jdbcTemplate);

        List<ProviderType> pending = repository.prepareDispatch(
                id,
                "hash-v1",
                List.of(ProviderType.DOUBAN, ProviderType.TMDB));

        assertEquals(List.of(ProviderType.TMDB), pending);
        org.mockito.Mockito.verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("raw_video_pipeline_runs")
                        && sql.contains("on conflict (raw_video_id, pipeline_version, step) do nothing")),
                any(MapSqlParameterSource.class));
        org.mockito.Mockito.verify(jdbcTemplate).batchUpdate(
                argThat(sql -> sql.contains("raw_video_enrichment_provider_runs")
                        && sql.contains("on conflict (raw_video_id, pipeline_version, provider_type) do nothing")),
                any(MapSqlParameterSource[].class));
    }

    @Test
    void completeProviderUpdatesProviderRunAndPipelineCounters() {
        NamedParameterJdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.update(
                        argThat(sql -> sql.contains("raw_video_enrichment_provider_runs")
                                && sql.contains("status = 'PENDING'")),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);
        when(jdbcTemplate.query(
                        argThat(sql -> sql.contains("raw_video_pipeline_runs")
                                && sql.contains("completed_count = least(expected_count, completed_count + 1)")
                                && sql.contains("returning expected_count")),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<ProviderCompletion> mapper = invocation.getArgument(2);
                    java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getInt("expected_count")).thenReturn(2);
                    when(rs.getInt("completed_count")).thenReturn(1);
                    when(rs.getInt("success_count")).thenReturn(1);
                    when(rs.getInt("failure_count")).thenReturn(0);
                    when(rs.getInt("retryable_failure_count")).thenReturn(0);
                    when(rs.getInt("permanent_failure_count")).thenReturn(0);
                    return List.of(mapper.mapRow(rs, 0));
                });
        MetadataPipelineRunRepository repository = new MetadataPipelineRunRepository(jdbcTemplate);

        ProviderCompletion completion = repository.completeProvider(
                id,
                "hash-v1",
                ProviderType.TMDB,
                true,
                false,
                null,
                null);

        assertTrue(completion.tracked());
        assertTrue(completion.newlyCompleted());
        assertEquals(2, completion.expectedCount());
        assertEquals(1, completion.completedCount());
    }
}
