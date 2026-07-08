package com.prodigalgal.ircs.search.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.search.SearchSyncWorkPayload;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SearchSyncMessage;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class SearchSyncWorkQueueWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SearchSyncProcessor syncProcessor = org.mockito.Mockito.mock(SearchSyncProcessor.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);

    @Test
    void processesClaimedRuntimeWorkAndCompletesIt() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID id = UUID.randomUUID();
        RuntimeWorkItem item = item(id, 1, SyncOperation.INDEX);
        queue.rawItems.add(item);
        SearchSyncWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(item);
        verify(syncProcessor).process(org.mockito.ArgumentMatchers.argThat(message ->
                id.equals(message.getEntityId())
                        && message.getEntityType() == SearchEntityType.RAW_VIDEO
                        && message.getOperation() == SyncOperation.INDEX));
        assertThat(worker.state().lastProcessed()).isEqualTo(1);
        assertThat(worker.state().consecutiveFailures()).isZero();
        assertThat(queue.heartbeats).contains(SearchSyncWorkTypes.RAW, SearchSyncWorkTypes.UNIFIED);
    }

    @Test
    void sendsFailedRuntimeWorkToDlqAfterMaxRetries() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID id = UUID.randomUUID();
        RuntimeWorkItem item = item(id, 5, SyncOperation.INDEX);
        queue.rawItems.add(item);
        doThrow(new RuntimeException("es down"))
                .when(syncProcessor)
                .process(org.mockito.ArgumentMatchers.any(SearchSyncMessage.class));
        SearchSyncWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isZero();
        assertThat(queue.failures).hasSize(1);
        assertThat(queue.failures.getFirst().retryable()).isFalse();
        assertThat(queue.failures.getFirst().item()).isEqualTo(item);
        assertThat(worker.state().lastFailed()).isEqualTo(1);
        assertThat(worker.state().consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void rawFailureDoesNotBlockUnifiedProcessing() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem raw = item(UUID.randomUUID(), 1, SyncOperation.INDEX);
        RuntimeWorkItem unified = item(SearchSyncWorkTypes.UNIFIED, UUID.randomUUID(), 1,
                SearchEntityType.UNIFIED_VIDEO, SyncOperation.INDEX);
        queue.rawItems.add(raw);
        queue.unifiedItems.add(unified);
        doAnswer(invocation -> {
            SearchSyncMessage message = invocation.getArgument(0);
            if (message.getEntityType() == SearchEntityType.RAW_VIDEO) {
                throw new RuntimeException("raw es down");
            }
            return null;
        }).when(syncProcessor).process(org.mockito.ArgumentMatchers.any(SearchSyncMessage.class));
        SearchSyncWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.failures).extracting(Failure::item).containsExactly(raw);
        assertThat(queue.completed).containsExactly(unified);
        assertThat(worker.state().lastProcessed()).isEqualTo(1);
        assertThat(worker.state().lastFailed()).isEqualTo(1);
        assertThat(worker.state().lanes().get(SearchSyncWorkTypes.RAW).lastRunState())
                .isEqualTo("DEPENDENCY_FAILURE");
        assertThat(worker.state().lanes().get(SearchSyncWorkTypes.UNIFIED).lastRunState())
                .isEqualTo("PROCESSED");
    }

    @Test
    void transientClaimFailureDoesNotKillWorkerLoop() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem unified = item(SearchSyncWorkTypes.UNIFIED, UUID.randomUUID(), 1,
                SearchEntityType.UNIFIED_VIDEO, SyncOperation.INDEX);
        queue.failRawClaim = true;
        queue.unifiedItems.add(unified);
        SearchSyncWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(unified);
        assertThat(worker.state().lanes().get(SearchSyncWorkTypes.RAW).lastFailed()).isEqualTo(1);
        assertThat(worker.state().lanes().get(SearchSyncWorkTypes.UNIFIED).lastProcessed()).isEqualTo(1);
        assertThat(queue.heartbeats).contains(SearchSyncWorkTypes.RAW, SearchSyncWorkTypes.UNIFIED);
    }

    @Test
    void singleLaneRunOnlyHeartbeatsThatLane() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        SearchSyncWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce(SearchSyncWorkTypes.RAW);

        assertThat(processed).isZero();
        assertThat(queue.heartbeats).contains(SearchSyncWorkTypes.RAW);
        assertThat(queue.heartbeats).doesNotContain(SearchSyncWorkTypes.UNIFIED);
    }

    @Test
    void scheduledTriggerSubmitsWorkWithoutRunningOnSchedulerThread() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem item = item(SearchSyncWorkTypes.UNIFIED, UUID.randomUUID(), 1,
                SearchEntityType.UNIFIED_VIDEO, SyncOperation.INDEX);
        queue.unifiedItems.add(item);
        RecordingExecutor executor = new RecordingExecutor();
        SearchSyncWorkQueueWorker worker = worker(queue, executor);

        worker.runUnifiedScheduled();

        assertThat(executor.tasks).hasSize(1);
        assertThat(queue.completed).isEmpty();

        executor.runNext();

        assertThat(queue.completed).containsExactly(item);
    }

    private SearchSyncWorkQueueWorker worker(FakeRuntimeWorkQueue queue) throws Exception {
        return worker(queue, Runnable::run);
    }

    private SearchSyncWorkQueueWorker worker(FakeRuntimeWorkQueue queue, Executor executor) throws Exception {
        SearchSyncWorkQueueWorker worker = new SearchSyncWorkQueueWorker(
                queue,
                objectMapper,
                syncProcessor,
                auditWriter,
                new SearchSyncWorkQueueMetrics(new SimpleMeterRegistry()),
                null,
                executor);
        set(worker, "batchSize", 10);
        set(worker, "maxRetries", 5);
        set(worker, "visibilitySeconds", 60L);
        set(worker, "maxBackoffSeconds", 300L);
        set(worker, "workerId", "test-worker");
        set(worker, "applicationName", "ircs-search-service");
        return worker;
    }

    private RuntimeWorkItem item(UUID id, int attempt, SyncOperation operation) throws Exception {
        return item(SearchSyncWorkTypes.RAW, id, attempt, SearchEntityType.RAW_VIDEO, operation);
    }

    private RuntimeWorkItem item(
            String taskType,
            UUID id,
            int attempt,
            SearchEntityType entityType,
            SyncOperation operation) throws Exception {
        SearchSyncWorkPayload payload = new SearchSyncWorkPayload(
                id,
                entityType,
                operation,
                "test",
                "trace-1");
        return new RuntimeWorkItem(
                taskType,
                entityType.name().toLowerCase(java.util.Locale.ROOT) + ":" + id,
                UUID.randomUUID().toString(),
                id.toString(),
                operation.name(),
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
        private final List<RuntimeWorkItem> rawItems = new ArrayList<>();
        private final List<RuntimeWorkItem> unifiedItems = new ArrayList<>();
        private final List<RuntimeWorkItem> completed = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private final List<String> heartbeats = new ArrayList<>();
        private boolean failRawClaim;

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
            if (SearchSyncWorkTypes.RAW.equals(taskType)) {
                if (failRawClaim) {
                    throw new IllegalStateException("valkey loading");
                }
                return List.copyOf(rawItems);
            }
            if (SearchSyncWorkTypes.UNIFIED.equals(taskType)) {
                return List.copyOf(unifiedItems);
            }
            return List.of();
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
            return new RuntimeWorkQueueCounts(rawItems.size(), 0, failures.size());
        }

        @Override
        public void heartbeatConsumer(String taskType, String ownerId, Duration ttl) {
            heartbeats.add(taskType);
        }
    }

    private record Failure(RuntimeWorkItem item, boolean retryable, Duration retryDelay, String reason) {
    }

    private static class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}
