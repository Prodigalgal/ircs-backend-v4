package com.prodigalgal.ircs.ops.maintenance.application;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class MaintenanceReindexCommandTest {

    private final NamedParameterJdbcTemplate jdbcTemplate =
            org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    private final MaintenanceSearchSyncClient searchSyncClient = org.mockito.Mockito.mock(MaintenanceSearchSyncClient.class);
    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);

    @BeforeEach
    void setUp() {
        when(configValues.reindexDevLimit()).thenReturn(5);
        when(configValues.reindexBatchSize()).thenReturn(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueUnifiedIndexDelegatesToSearchOwnerService() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(first, second));
        when(searchSyncClient.enqueueIndex(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.eq(SearchEntityType.UNIFIED_VIDEO),
                org.mockito.Mockito.eq("corr-1")))
                .thenReturn(2);

        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.enqueueUnifiedIndex("corr-1");

        assertEquals("search-reindex-unified", result.taskName());
        assertEquals(2, result.selectedCount());
        assertEquals(2, result.publishedCount());
        assertEquals(List.of(first, second), result.entityIds());

        verify(searchSyncClient).enqueueIndex(
                List.of(first, second),
                SearchEntityType.UNIFIED_VIDEO,
                "corr-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueRawIndexDelegatesToSearchOwnerService() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(first, second));
        when(searchSyncClient.enqueueIndex(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.eq(SearchEntityType.RAW_VIDEO),
                org.mockito.Mockito.eq("corr-raw")))
                .thenReturn(2);

        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.enqueueRawIndex("corr-raw");

        assertEquals("search-reindex-raw", result.taskName());
        assertEquals(2, result.selectedCount());
        assertEquals(2, result.publishedCount());
        assertEquals(List.of(first, second), result.entityIds());

        verify(searchSyncClient).enqueueIndex(
                List.of(first, second),
                SearchEntityType.RAW_VIDEO,
                "corr-raw");
    }

    @Test
    void hardResetUnifiedIndexDelegatesToSearchOwnerService() {
        when(searchSyncClient.hardResetIndex(SearchEntityType.UNIFIED_VIDEO, "corr-reset"))
                .thenReturn(5);
        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.hardResetUnifiedIndex("corr-reset");

        assertEquals("aggregation-reset.search-hard-reset", result.taskName());
        assertEquals(1, result.selectedCount());
        assertEquals(5, result.publishedCount());
        assertEquals(List.of(), result.entityIds());
        verify(searchSyncClient).hardResetIndex(SearchEntityType.UNIFIED_VIDEO, "corr-reset");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueUnifiedIndexSkipsRabbitWhenNoCandidateExists() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.enqueueUnifiedIndex();

        assertEquals(0, result.selectedCount());
        assertEquals(0, result.publishedCount());
        verify(searchSyncClient, never()).enqueueIndex(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.anyString());
    }

    @Test
    void zeroDevLimitDoesNotQueryOrPublish() {
        when(configValues.reindexDevLimit()).thenReturn(0);
        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.enqueueUnifiedIndex();

        assertEquals(0, result.selectedCount());
        verify(jdbcTemplate, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        verify(searchSyncClient, never()).enqueueIndex(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesCurrentDevLimitForEachRun() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(configValues.reindexDevLimit()).thenReturn(7);
        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        command.enqueueUnifiedIndex();

        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(anyString(), paramsCaptor.capture(), any(RowMapper.class));
        MapSqlParameterSource params = (MapSqlParameterSource) paramsCaptor.getValue();
        assertEquals(7, params.getValue("limit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueAllUnifiedIndexPublishesByCursorBatches() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of(third));
        when(searchSyncClient.enqueueIndex(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.eq(SearchEntityType.UNIFIED_VIDEO),
                org.mockito.Mockito.eq("corr-all")))
                .thenReturn(2)
                .thenReturn(1);

        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.enqueueAllUnifiedIndex("corr-all");

        assertEquals("search-reindex-unified-all", result.taskName());
        assertEquals(3, result.selectedCount());
        assertEquals(3, result.publishedCount());
        assertEquals(List.of(first, second, third), result.entityIds());
        verify(searchSyncClient).enqueueIndex(
                List.of(first, second),
                SearchEntityType.UNIFIED_VIDEO,
                "corr-all");
        verify(searchSyncClient).enqueueIndex(
                List.of(third),
                SearchEntityType.UNIFIED_VIDEO,
                "corr-all");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueAllRawIndexSkipsPublishWhenNoRowsExist() {
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        MaintenanceReindexCommand command = new MaintenanceReindexCommand(
                jdbcTemplate, searchSyncClient, configValues);

        MaintenanceRunResult result = command.enqueueAllRawIndex("corr-empty");

        assertEquals("search-reindex-raw-all", result.taskName());
        assertEquals(0, result.selectedCount());
        assertEquals(0, result.publishedCount());
        verify(searchSyncClient, never()).enqueueIndex(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.anyString());
    }
}
