package com.prodigalgal.ircs.search.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.AuditClass;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkPayload;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import com.prodigalgal.ircs.search.document.AuditEventSearchDocument;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AuditEsReplicationWorkQueueWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditEsReplicationRepository repository = org.mockito.Mockito.mock(AuditEsReplicationRepository.class);
    private final SearchIndexService searchIndexService = org.mockito.Mockito.mock(SearchIndexService.class);

    @Test
    void indexesAuditDocumentAndCompletesWorkItem() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem item = item(1);
        AuditEventSearchDocument document = new AuditEventSearchDocument();
        document.setId("request_audit_logs:" + UUID.randomUUID());
        queue.items.add(item);
        when(repository.findDocument(any(AuditReplicationWorkPayload.class))).thenReturn(Optional.of(document));
        AuditEsReplicationWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        verify(searchIndexService).saveAudit(document);
        assertThat(queue.completed).containsExactly(item);
        assertThat(queue.heartbeats).contains(AuditReplicationWorkTypes.ES_REPLICATION);
    }

    @Test
    void missingSourceRecordIsTreatedAsCompleted() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem item = item(1);
        queue.items.add(item);
        when(repository.findDocument(any(AuditReplicationWorkPayload.class))).thenReturn(Optional.empty());
        AuditEsReplicationWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(item);
        org.mockito.Mockito.verifyNoInteractions(searchIndexService);
    }

    @Test
    void temporaryIndexFailureRetriesWorkItem() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem item = item(2);
        AuditEventSearchDocument document = new AuditEventSearchDocument();
        document.setId("request_audit_logs:" + UUID.randomUUID());
        queue.items.add(item);
        when(repository.findDocument(any(AuditReplicationWorkPayload.class))).thenReturn(Optional.of(document));
        doThrow(new IllegalStateException("es down")).when(searchIndexService).saveAudit(document);
        AuditEsReplicationWorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isZero();
        assertThat(queue.failures).hasSize(1);
        assertThat(queue.failures.getFirst().retryable()).isTrue();
        assertThat(queue.failures.getFirst().item()).isEqualTo(item);
    }

    @Test
    void permanentIndexFailureMovesToDlqAfterMaxRetries() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem item = item(8);
        AuditEventSearchDocument document = new AuditEventSearchDocument();
        document.setId("request_audit_logs:" + UUID.randomUUID());
        queue.items.add(item);
        when(repository.findDocument(any(AuditReplicationWorkPayload.class))).thenReturn(Optional.of(document));
        doThrow(new IllegalStateException("es down")).when(searchIndexService).saveAudit(document);
        AuditEsReplicationWorkQueueWorker worker = worker(queue);

        worker.runOnce();

        assertThat(queue.failures).hasSize(1);
        assertThat(queue.failures.getFirst().retryable()).isFalse();
    }

    @Test
    void globalReplicationSwitchDisablesWorkerConsumption() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        queue.items.add(item(1));
        com.prodigalgal.ircs.search.support.SystemConfigRepository configRepository =
                org.mockito.Mockito.mock(com.prodigalgal.ircs.search.support.SystemConfigRepository.class);
        when(configRepository.findValue("app.audit.es-replication.enabled")).thenReturn(Optional.of("false"));
        when(configRepository.findValue("app.search.audit-es-replication.worker.enabled")).thenReturn(Optional.of("true"));
        AuditEsReplicationWorkQueueWorker worker = worker(queue, configRepository);

        int processed = worker.runOnce();

        assertThat(processed).isZero();
        assertThat(queue.claimCalls).isZero();
        verifyNoInteractions(repository, searchIndexService);
    }

    @Test
    void deploymentGlobalReplicationSwitchIsUsedWhenRuntimeConfigMissing() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        queue.items.add(item(1));
        com.prodigalgal.ircs.search.support.SystemConfigRepository configRepository =
                org.mockito.Mockito.mock(com.prodigalgal.ircs.search.support.SystemConfigRepository.class);
        when(configRepository.findValue("app.audit.es-replication.enabled")).thenReturn(Optional.empty());
        when(configRepository.findValue("app.search.audit-es-replication.worker.enabled")).thenReturn(Optional.of("true"));
        AuditEsReplicationWorkQueueWorker worker = worker(queue, configRepository);
        set(worker, "replicationEnabledByDeployment", false);

        int processed = worker.runOnce();

        assertThat(processed).isZero();
        assertThat(queue.claimCalls).isZero();
        verifyNoInteractions(repository, searchIndexService);
    }

    @Test
    void scheduledTriggerSubmitsWorkWithoutRunningOnSchedulerThread() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkItem item = item(1);
        queue.items.add(item);
        AuditEventSearchDocument document = new AuditEventSearchDocument();
        document.setId("request_audit_logs:" + UUID.randomUUID());
        when(repository.findDocument(any(AuditReplicationWorkPayload.class))).thenReturn(Optional.of(document));
        RecordingExecutor executor = new RecordingExecutor();
        AuditEsReplicationWorkQueueWorker worker = worker(queue, null, executor);

        worker.runScheduled();

        assertThat(executor.tasks).hasSize(1);
        assertThat(queue.completed).isEmpty();

        executor.runNext();

        assertThat(queue.completed).containsExactly(item);
    }

    private AuditEsReplicationWorkQueueWorker worker(FakeRuntimeWorkQueue queue) throws Exception {
        return worker(queue, null, Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private AuditEsReplicationWorkQueueWorker worker(
            FakeRuntimeWorkQueue queue,
            com.prodigalgal.ircs.search.support.SystemConfigRepository configRepository) throws Exception {
        return worker(queue, configRepository, Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private AuditEsReplicationWorkQueueWorker worker(
            FakeRuntimeWorkQueue queue,
            com.prodigalgal.ircs.search.support.SystemConfigRepository configRepository,
            Executor executor) throws Exception {
        ObjectProvider<com.prodigalgal.ircs.search.support.SystemConfigRepository> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configRepository);
        AuditEsReplicationWorkQueueWorker worker = new AuditEsReplicationWorkQueueWorker(
                queue,
                objectMapper,
                repository,
                searchIndexService,
                provider,
                executor);
        set(worker, "enabledByDeployment", true);
        set(worker, "replicationEnabledByDeployment", true);
        set(worker, "batchSize", 100);
        set(worker, "visibilitySeconds", 600L);
        set(worker, "maxRetries", 8);
        set(worker, "maxBackoffSeconds", 900L);
        set(worker, "workerId", "test-audit-worker");
        set(worker, "applicationName", "ircs-search-service");
        return worker;
    }

    private RuntimeWorkItem item(int attempt) throws Exception {
        UUID sourceId = UUID.randomUUID();
        AuditReplicationWorkPayload payload = new AuditReplicationWorkPayload(
                AuditClass.SYSTEM,
                AuditEsReplicationRepository.REQUEST_AUDIT_LOGS,
                sourceId,
                "UPSERT",
                null);
        return new RuntimeWorkItem(
                AuditReplicationWorkTypes.ES_REPLICATION,
                AuditReplicationWorkTypes.taskId(payload.sourceTable(), sourceId),
                UUID.randomUUID().toString(),
                sourceId.toString(),
                "UPSERT",
                objectMapper.writeValueAsString(payload),
                "PROCESSING",
                attempt,
                Instant.now().minusSeconds(5),
                Instant.now(),
                Instant.now(),
                Instant.now().plusSeconds(600),
                "test-audit-worker",
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
        private final List<String> heartbeats = new ArrayList<>();
        private int claimCalls;

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
            claimCalls++;
            return AuditReplicationWorkTypes.ES_REPLICATION.equals(taskType) ? List.copyOf(items) : List.of();
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
