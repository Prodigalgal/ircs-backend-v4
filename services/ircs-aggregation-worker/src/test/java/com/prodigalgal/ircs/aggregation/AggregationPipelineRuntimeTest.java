package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class AggregationPipelineRuntimeTest {

    @Mock
    private JdbcAggregationRepository repository;

    @Test
    void runsHandlersInV1OrderAndReturnsCoverR2SyncIds() {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID coverImageId = UUID.randomUUID();
        when(repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId)).thenReturn(List.of(coverImageId));
        RecordingTransactionOperations transactions = new RecordingTransactionOperations();
        AggregationPipelineRuntime runtime = AggregationPipelineRuntime.forTest(repository, transactions);

        AggregationPipelineExecution execution = runtime.run(unifiedVideoId);

        assertTrue(execution.successful());
        assertEquals(List.of(coverImageId), execution.coverR2SyncImageIds());
        assertEquals(7, transactions.calls());
        InOrder ordered = inOrder(repository);
        ordered.verify(repository).rebuildUnifiedPipelineBasicAttributes(unifiedVideoId);
        ordered.verify(repository).rebuildUnifiedPipelineExternalIds(unifiedVideoId);
        ordered.verify(repository).rebuildUnifiedPipelineMetadataRelations(unifiedVideoId);
        ordered.verify(repository).rebuildUnifiedPipelineDynamicFields(unifiedVideoId);
        ordered.verify(repository).rebuildUnifiedPipelineCategory(unifiedVideoId);
        ordered.verify(repository).rebuildUnifiedAdultAssessment(unifiedVideoId);
        ordered.verify(repository).rebuildUnifiedPipelineCoverImage(unifiedVideoId);
    }

    @Test
    void continuesAfterStageFailureAndRecordsFailedStage() {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID coverImageId = UUID.randomUUID();
        doThrow(new IllegalStateException("metadata failed"))
                .when(repository)
                .rebuildUnifiedPipelineMetadataRelations(unifiedVideoId);
        when(repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId)).thenReturn(List.of(coverImageId));
        RecordingTransactionOperations transactions = new RecordingTransactionOperations();
        AggregationPipelineRuntime runtime = AggregationPipelineRuntime.forTest(repository, transactions);

        AggregationPipelineExecution execution = runtime.run(unifiedVideoId);

        assertFalse(execution.successful());
        assertEquals(List.of(coverImageId), execution.coverR2SyncImageIds());
        assertEquals(7, transactions.calls());
        assertEquals(1, execution.failures().size());
        assertEquals(AggregationPipelineStage.METADATA, execution.failures().getFirst().stage());
        assertEquals("MetadataHandler", execution.failures().getFirst().handlerName());
        assertEquals(30, execution.failures().getFirst().v1Order());
        verify(repository).rebuildUnifiedPipelineDynamicFields(unifiedVideoId);
        verify(repository).rebuildUnifiedPipelineCategory(unifiedVideoId);
        verify(repository).rebuildUnifiedAdultAssessment(unifiedVideoId);
        verify(repository).rebuildUnifiedPipelineCoverImage(unifiedVideoId);
    }

    @Test
    void coverStageFailureDoesNotReturnR2SyncIds() {
        UUID unifiedVideoId = UUID.randomUUID();
        doThrow(new IllegalStateException("cover failed"))
                .when(repository)
                .rebuildUnifiedPipelineCoverImage(unifiedVideoId);
        RecordingTransactionOperations transactions = new RecordingTransactionOperations();
        AggregationPipelineRuntime runtime = AggregationPipelineRuntime.forTest(repository, transactions);

        AggregationPipelineExecution execution = runtime.run(unifiedVideoId);

        assertFalse(execution.successful());
        assertTrue(execution.coverR2SyncImageIds().isEmpty());
        assertEquals(7, transactions.calls());
        assertEquals(List.of(AggregationPipelineStage.COVER_IMAGE), execution.failures().stream()
                .map(AggregationPipelineStageFailure::stage)
                .toList());
    }

    private static final class RecordingTransactionOperations implements TransactionOperations {

        private int calls;

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            calls++;
            return action.doInTransaction(null);
        }

        int calls() {
            return calls;
        }
    }
}
