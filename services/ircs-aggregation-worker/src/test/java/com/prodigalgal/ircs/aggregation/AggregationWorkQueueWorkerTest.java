package com.prodigalgal.ircs.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkPayload;
import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregationWorkQueueWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AggregationService aggregationService = org.mockito.Mockito.mock(AggregationService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);

    @Test
    void completesBoundAggregationWork() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID rawVideoId = UUID.randomUUID();
        RuntimeWorkItem item = item(rawVideoId, 1);
        queue.items.add(item);
        when(aggregationService.aggregateRuntimeWorkBatch(List.of(rawVideoId)))
                .thenReturn(List.of(AggregationResult.bound(rawVideoId, UUID.randomUUID())));
        AggregationWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(item);
        assertThat(queue.failures).isEmpty();
        assertThat(queue.heartbeats).isGreaterThan(0);
        assertThat(worker.state().lastRunState()).isEqualTo("PROCESSED");
        assertThat(worker.state().lastProcessed()).isEqualTo(1);
        verify(aggregationService).aggregateRuntimeWorkBatch(List.of(rawVideoId));
    }

    @Test
    void retriesSkippedAggregationWork() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID rawVideoId = UUID.randomUUID();
        RuntimeWorkItem item = item(rawVideoId, 1);
        queue.items.add(item);
        when(aggregationService.aggregateRuntimeWorkBatch(List.of(rawVideoId)))
                .thenReturn(List.of(AggregationResult.skipped(rawVideoId, "NOT_ELIGIBLE")));
        AggregationWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).isEmpty();
        assertThat(queue.failures).hasSize(1);
        assertThat(queue.failures.getFirst().retryable()).isTrue();
        assertThat(queue.failures.getFirst().reason()).isEqualTo("NOT_ELIGIBLE");
        assertThat(worker.state().lastRunState()).isEqualTo("DEPENDENCY_FAILURE");
        assertThat(worker.state().lastFailed()).isEqualTo(1);
    }

    @Test
    void processesClaimedItemsInSmallChunks() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        List<UUID> rawVideoIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (UUID rawVideoId : rawVideoIds) {
            queue.items.add(item(rawVideoId, 1));
        }
        when(aggregationService.aggregateRuntimeWorkBatch(List.of(rawVideoIds.get(0), rawVideoIds.get(1))))
                .thenReturn(List.of(
                        AggregationResult.bound(rawVideoIds.get(0), UUID.randomUUID()),
                        AggregationResult.bound(rawVideoIds.get(1), UUID.randomUUID())));
        when(aggregationService.aggregateRuntimeWorkBatch(List.of(rawVideoIds.get(2))))
                .thenReturn(List.of(AggregationResult.bound(rawVideoIds.get(2), UUID.randomUUID())));
        AggregationWorkQueueWorker worker = worker(queue);
        set(worker, "processingChunkSize", 2);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(3);
        assertThat(queue.completed).hasSize(3);
        verify(aggregationService).aggregateRuntimeWorkBatch(List.of(rawVideoIds.get(0), rawVideoIds.get(1)));
        verify(aggregationService).aggregateRuntimeWorkBatch(List.of(rawVideoIds.get(2)));
    }

    @Test
    void exposesCurrentStageWhileAggregationBatchIsRunning() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID rawVideoId = UUID.randomUUID();
        RuntimeWorkItem item = item(rawVideoId, 1);
        queue.items.add(item);
        AggregationWorkQueueWorker worker = worker(queue);
        when(aggregationService.aggregateRuntimeWorkBatch(List.of(rawVideoId))).thenAnswer(invocation -> {
            AggregationWorkQueueState state = worker.state();
            assertThat(state.currentStage()).isEqualTo("AGGREGATE_BATCH");
            assertThat(state.currentRawVideoId()).isEqualTo(rawVideoId.toString());
            assertThat(state.currentTaskId()).isEqualTo(item.taskId());
            assertThat(state.currentBatchSize()).isEqualTo(1);
            assertThat(state.currentStageStartedAt()).isNotNull();
            return List.of(AggregationResult.bound(rawVideoId, UUID.randomUUID()));
        });

        worker.runOnce();

        assertThat(worker.state().currentStage()).isNull();
        verify(aggregationService, times(1)).aggregateRuntimeWorkBatch(List.of(rawVideoId));
    }

    private AggregationWorkQueueWorker worker(FakeRuntimeWorkQueue queue) throws Exception {
        AggregationWorkQueueWorker worker = new AggregationWorkQueueWorker(
                queue,
                objectMapper,
                aggregationService,
                auditWriter);
        set(worker, "batchSize", 10);
        set(worker, "processingChunkSize", 5);
        set(worker, "visibilitySeconds", 60L);
        set(worker, "maxRetries", 5);
        set(worker, "maxBackoffSeconds", 300L);
        set(worker, "workerId", "test-worker");
        set(worker, "applicationName", "ircs-aggregation-worker");
        return worker;
    }

    private RuntimeWorkItem item(UUID rawVideoId, int attempt) throws Exception {
        AggregationWorkPayload payload = new AggregationWorkPayload(
                rawVideoId,
                "hash-v1",
                "test",
                "unit-test");
        return new RuntimeWorkItem(
                AggregationWorkTypes.RAW_VIDEO,
                AggregationWorkTypes.taskId(rawVideoId),
                UUID.randomUUID().toString(),
                rawVideoId.toString(),
                "hash-v1",
                objectMapper.writeValueAsString(payload),
                "PROCESSING",
                attempt,
                Instant.now().minusSeconds(5),
                Instant.now(),
                Instant.now(),
                Instant.now().plusSeconds(60),
                "test-worker",
                null);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class FakeRuntimeWorkQueue implements RuntimeWorkQueue {
        private final List<RuntimeWorkItem> items = new ArrayList<>();
        private final List<RuntimeWorkItem> completed = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private int heartbeats;

        @Override
        public void submit(RuntimeWorkItemRequest request) {
        }

        @Override
        public void submit(RuntimeWorkItemRequest request, Duration delay) {
        }

        @Override
        public void submitAfterCommit(RuntimeWorkItemRequest request) {
        }

        @Override
        public void submitAfterCommit(RuntimeWorkItemRequest request, Duration delay) {
        }

        @Override
        public List<RuntimeWorkItem> claim(String taskType, String ownerId, int limit, Duration visibilityTimeout) {
            return AggregationWorkTypes.RAW_VIDEO.equals(taskType) ? List.copyOf(items) : List.of();
        }

        @Override
        public boolean complete(RuntimeWorkItem item) {
            completed.add(item);
            return true;
        }

        @Override
        public boolean fail(RuntimeWorkItem item, boolean retryable, Duration retryDelay, String reason) {
            failures.add(new Failure(item, retryable, retryDelay, reason));
            return true;
        }

        @Override
        public int requeueExpired(String taskType, int limit) {
            return 0;
        }

        @Override
        public RuntimeWorkQueueCounts counts(String taskType) {
            return new RuntimeWorkQueueCounts(items.size(), 0, failures.size());
        }

        @Override
        public void heartbeatConsumer(String taskType, String ownerId, Duration ttl) {
            heartbeats++;
        }
    }

    private record Failure(RuntimeWorkItem item, boolean retryable, Duration retryDelay, String reason) {
    }
}
