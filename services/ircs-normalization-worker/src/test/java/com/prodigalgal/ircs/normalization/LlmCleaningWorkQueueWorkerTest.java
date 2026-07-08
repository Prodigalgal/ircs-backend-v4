package com.prodigalgal.ircs.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkPayload;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
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

class LlmCleaningWorkQueueWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmCleaningService cleaningService = org.mockito.Mockito.mock(LlmCleaningService.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);

    @Test
    void completesQueuedTermsAfterSuccessfulCleaning() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID rawId = UUID.randomUUID();
        RuntimeWorkItem item = item("genre", rawId, 1);
        queue.items.add(item);
        when(cleaningService.cleanQueued(LlmCleaningKind.GENRE, List.of(rawId)))
                .thenReturn(new LlmCleaningService.LlmCleaningResult(
                        LlmCleaningKind.GENRE,
                        "SUCCESS",
                        1,
                        1,
                        0,
                        "CLEANED"));
        LlmCleaningWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(item);
        assertThat(queue.failures).isEmpty();
        verify(cleaningService).cleanQueued(LlmCleaningKind.GENRE, List.of(rawId));
    }

    @Test
    void retriesProviderSkipsWithoutCompletingWork() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID rawId = UUID.randomUUID();
        RuntimeWorkItem item = item("area", rawId, 1);
        queue.items.add(item);
        when(cleaningService.cleanQueued(LlmCleaningKind.AREA, List.of(rawId)))
                .thenReturn(LlmCleaningService.LlmCleaningResult.skipped(
                        LlmCleaningKind.AREA,
                        "PROVIDER_TIMEOUT"));
        LlmCleaningWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).isEmpty();
        assertThat(queue.failures).hasSize(1);
        assertThat(queue.failures.getFirst().retryable()).isTrue();
        assertThat(queue.failures.getFirst().reason()).isEqualTo("PROVIDER_TIMEOUT");
    }

    @Test
    void completesDryRunSkipsSoQueueDoesNotSpinForever() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID rawId = UUID.randomUUID();
        RuntimeWorkItem item = item("language", rawId, 1);
        queue.items.add(item);
        when(cleaningService.cleanQueued(LlmCleaningKind.LANGUAGE, List.of(rawId)))
                .thenReturn(LlmCleaningService.LlmCleaningResult.skipped(
                        LlmCleaningKind.LANGUAGE,
                        "DRY_RUN_SKIPPED"));
        LlmCleaningWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(item);
        assertThat(queue.failures).isEmpty();
    }

    private LlmCleaningWorkQueueWorker worker(FakeRuntimeWorkQueue queue) throws Exception {
        LlmCleaningWorkQueueWorker worker = new LlmCleaningWorkQueueWorker(
                queue,
                objectMapper,
                cleaningService,
                auditWriter,
                null);
        set(worker, "batchSize", 10);
        set(worker, "visibilitySeconds", 60L);
        set(worker, "maxRetries", 5);
        set(worker, "maxBackoffSeconds", 300L);
        set(worker, "workerId", "test-worker");
        set(worker, "applicationName", "ircs-normalization-worker");
        return worker;
    }

    private RuntimeWorkItem item(String kind, UUID rawId, int attempt) throws Exception {
        LlmCleaningWorkPayload payload = new LlmCleaningWorkPayload(
                kind,
                rawId,
                kind + "-raw",
                "unit-test",
                "unit-test");
        return new RuntimeWorkItem(
                LlmCleaningWorkTypes.RAW_TERM,
                LlmCleaningWorkTypes.taskId(kind, rawId),
                UUID.randomUUID().toString(),
                rawId.toString(),
                kind + "-raw",
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
            return LlmCleaningWorkTypes.RAW_TERM.equals(taskType) ? List.copyOf(items) : List.of();
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
    }

    private record Failure(RuntimeWorkItem item, boolean retryable, Duration retryDelay, String reason) {
    }
}
