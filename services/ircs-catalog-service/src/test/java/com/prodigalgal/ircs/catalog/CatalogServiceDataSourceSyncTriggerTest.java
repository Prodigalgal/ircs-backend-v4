package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CatalogServiceDataSourceSyncTriggerTest {

    private final JdbcCatalogRepository repository = org.mockito.Mockito.mock(JdbcCatalogRepository.class);
    private final CatalogRemoteCategorySyncService remoteCategorySyncService =
            org.mockito.Mockito.mock(CatalogRemoteCategorySyncService.class);
    private final CatalogService service = new CatalogService(
            repository,
            new ObjectMapper(),
            org.mockito.Mockito.mock(CatalogFetchSampleClient.class),
            remoteCategorySyncService,
            CatalogReadModelCache.disabled());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createDataSourceTriggersRemoteCategorySyncLikeV1() {
        DataSourceRead saved = dataSource(UUID.randomUUID(), "Created");
        when(repository.createDataSource(any())).thenReturn(saved);
        when(remoteCategorySyncService.syncDataSourceCategories(saved.id()))
                .thenReturn(success(saved.id()));

        DataSourceRead result = service.createDataSource(request(null, "Created"));

        assertThat(result).isEqualTo(saved);
        verify(remoteCategorySyncService).syncDataSourceCategories(saved.id());
    }

    @Test
    void updateDataSourceTriggersRemoteCategorySyncLikeV1() {
        UUID id = UUID.randomUUID();
        DataSourceRead saved = dataSource(id, "Updated");
        when(repository.updateDataSource(eq(id), any(), eq(false))).thenReturn(Optional.of(saved));
        when(remoteCategorySyncService.syncDataSourceCategories(id)).thenReturn(success(id));

        DataSourceRead result = service.updateDataSource(id, request(id, "Updated"));

        assertThat(result).isEqualTo(saved);
        verify(remoteCategorySyncService).syncDataSourceCategories(id);
    }

    @Test
    void patchDataSourceTriggersOnlyWhenRowExists() {
        UUID id = UUID.randomUUID();
        DataSourceRead saved = dataSource(id, "Patched");
        when(repository.updateDataSource(eq(id), any(), eq(true))).thenReturn(Optional.of(saved));
        when(remoteCategorySyncService.syncDataSourceCategories(id)).thenReturn(success(id));

        Optional<DataSourceRead> result = service.patchDataSource(id, request(id, "Patched"));

        assertThat(result).contains(saved);
        verify(remoteCategorySyncService).syncDataSourceCategories(id);
    }

    @Test
    void patchDataSourceDoesNotTriggerWhenRowIsMissing() {
        UUID id = UUID.randomUUID();
        when(repository.updateDataSource(eq(id), any(), eq(true))).thenReturn(Optional.empty());

        Optional<DataSourceRead> result = service.patchDataSource(id, request(id, "Missing"));

        assertThat(result).isEmpty();
        verify(remoteCategorySyncService, never()).syncDataSourceCategories(any(UUID.class));
    }

    @Test
    void remoteCategorySyncFailureDoesNotFailDataSourceWrite() {
        UUID id = UUID.randomUUID();
        DataSourceRead saved = dataSource(id, "Created");
        when(repository.createDataSource(any())).thenReturn(saved);
        when(remoteCategorySyncService.syncDataSourceCategories(id)).thenThrow(new IllegalStateException("remote down"));

        DataSourceRead result = service.createDataSource(request(null, "Created"));

        assertThat(result).isEqualTo(saved);
        verify(remoteCategorySyncService).syncDataSourceCategories(id);
    }

    @Test
    void remoteCategorySyncRunsAfterCommitWhenTransactionSynchronizationIsActive() {
        UUID id = UUID.randomUUID();
        DataSourceRead saved = dataSource(id, "Created");
        when(repository.createDataSource(any())).thenReturn(saved);
        when(remoteCategorySyncService.syncDataSourceCategories(id)).thenReturn(success(id));
        TransactionSynchronizationManager.initSynchronization();

        DataSourceRead result = service.createDataSource(request(null, "Created"));

        assertThat(result).isEqualTo(saved);
        verify(remoteCategorySyncService, never()).syncDataSourceCategories(any(UUID.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(remoteCategorySyncService).syncDataSourceCategories(id);
    }

    private DataSourceAdminRequest request(UUID id, String name) {
        return new DataSourceAdminRequest(
                id,
                name,
                "https://example.test/",
                "api.php/provide/vod",
                "{\"ac\":\"list\",\"pg\":\"{page}\"}",
                "api.php/provide/vod",
                "{\"ac\":\"detail\",\"ids\":\"{ids}\"}",
                "{}");
    }

    private DataSourceRead dataSource(UUID id, String name) {
        return new DataSourceRead(
                id,
                name,
                "https://example.test",
                "/api.php/provide/vod",
                "{\"ac\":\"list\",\"pg\":\"{page}\"}",
                "/api.php/provide/vod",
                "{\"ac\":\"detail\",\"ids\":\"{ids}\"}",
                "{}",
                Instant.parse("2026-06-09T00:00:00Z"),
                Instant.parse("2026-06-09T00:00:00Z"));
    }

    private CatalogRemoteCategorySyncService.CategorySyncResult success(UUID id) {
        return new CatalogRemoteCategorySyncService.CategorySyncResult(
                id,
                "https://example.test/api.php/provide/vod?ac=list&pg=1",
                true,
                2,
                1,
                1,
                0,
                null);
    }
}
