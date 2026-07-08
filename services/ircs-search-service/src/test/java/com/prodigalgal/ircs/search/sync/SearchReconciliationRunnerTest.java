package com.prodigalgal.ircs.search.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import com.prodigalgal.ircs.search.repository.SearchDocumentJdbcRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchReconciliationRunnerTest {

    @Test
    void enqueuesDbRecordsMissingFromSearchIndexWhenExecuteEnabled() throws Exception {
        SearchDocumentJdbcRepository documentRepository = org.mockito.Mockito.mock(SearchDocumentJdbcRepository.class);
        SearchIndexService searchIndexService = org.mockito.Mockito.mock(SearchIndexService.class);
        SearchSyncWorkPublisher workPublisher = org.mockito.Mockito.mock(SearchSyncWorkPublisher.class);
        UUID present = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(documentRepository.findNextRawVideoIds(null, 10)).thenReturn(List.of(present, missing));
        when(documentRepository.findNextRawVideoIds(missing, 10)).thenReturn(List.of());
        when(documentRepository.findNextUnifiedVideoIds(null, 10)).thenReturn(List.of());
        when(searchIndexService.existingRawIds(List.of(present, missing))).thenReturn(Set.of(present));
        when(workPublisher.enqueueBatch(
                List.of(missing),
                SearchEntityType.RAW_VIDEO,
                SyncOperation.INDEX,
                "search-reconciliation",
                null)).thenReturn(1);
        SearchReconciliationRunner runner = new SearchReconciliationRunner(
                documentRepository,
                searchIndexService,
                workPublisher,
                org.mockito.Mockito.mock(WorkerJobAuditWriter.class),
                SearchDistributedLockRunner.local());
        set(runner, "batchSize", 10);
        set(runner, "maxBatches", 10);
        set(runner, "dryRun", false);
        set(runner, "executeGate", true);

        SearchReconciliationRunner.ReconciliationResult result = runner.runOnce();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.enqueued()).isEqualTo(1);
        verify(workPublisher).enqueueBatch(
                List.of(missing),
                SearchEntityType.RAW_VIDEO,
                SyncOperation.INDEX,
                "search-reconciliation",
                null);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
