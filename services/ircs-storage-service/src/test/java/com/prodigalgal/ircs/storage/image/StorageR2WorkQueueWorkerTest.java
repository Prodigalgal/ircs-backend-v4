package com.prodigalgal.ircs.storage.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import com.prodigalgal.ircs.common.storage.StorageWorkPayload;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
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

class StorageR2WorkQueueWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AvatarSyncService avatarSyncService = org.mockito.Mockito.mock(AvatarSyncService.class);
    private final CoverImageR2SyncService coverImageR2SyncService = org.mockito.Mockito.mock(CoverImageR2SyncService.class);
    private final R2ObjectStorage r2ObjectStorage = org.mockito.Mockito.mock(R2ObjectStorage.class);
    private final WorkerJobAuditWriter auditWriter = org.mockito.Mockito.mock(WorkerJobAuditWriter.class);

    @Test
    void inactiveR2DoesNotClaimQueuedWork() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        queue.items.add(item(StorageWorkTypes.AVATAR_SYNC, UUID.randomUUID(), 1));
        when(r2ObjectStorage.isActive()).thenReturn(false);
        StorageR2WorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isZero();
        assertThat(queue.claimCalls).isZero();
        verify(avatarSyncService, never()).sync(org.mockito.ArgumentMatchers.any());
        verify(coverImageR2SyncService, never()).sync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completesSuccessfulAvatarSyncWork() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID memberId = UUID.randomUUID();
        RuntimeWorkItem item = item(StorageWorkTypes.AVATAR_SYNC, memberId, 1);
        queue.items.add(item);
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(avatarSyncService.sync(memberId))
                .thenReturn(AvatarSyncService.AvatarSyncResult.synced("avatars/a.png", "https://img.example/avatars/a.png"));
        StorageR2WorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).containsExactly(item);
        assertThat(queue.failures).isEmpty();
        verify(avatarSyncService).sync(memberId);
    }

    @Test
    void retriesFailedCoverR2SyncWork() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        UUID imageId = UUID.randomUUID();
        RuntimeWorkItem item = item(StorageWorkTypes.COVER_R2_SYNC, imageId, 1);
        queue.items.add(item);
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(coverImageR2SyncService.sync(imageId))
                .thenReturn(CoverImageR2SyncService.CoverImageR2SyncResult.failed("r2 upload failed"));
        StorageR2WorkQueueWorker worker = worker(queue);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(queue.completed).isEmpty();
        assertThat(queue.failures).hasSize(1);
        assertThat(queue.failures.getFirst().retryable()).isTrue();
        assertThat(queue.failures.getFirst().reason()).isEqualTo("r2 upload failed");
    }

    private StorageR2WorkQueueWorker worker(FakeRuntimeWorkQueue queue) throws Exception {
        StorageR2WorkQueueWorker worker = new StorageR2WorkQueueWorker(
                queue,
                objectMapper,
                avatarSyncService,
                coverImageR2SyncService,
                r2ObjectStorage,
                auditWriter,
                null);
        set(worker, "batchSize", 10);
        set(worker, "visibilitySeconds", 60L);
        set(worker, "maxRetries", 5);
        set(worker, "maxBackoffSeconds", 300L);
        set(worker, "workerId", "test-worker");
        set(worker, "applicationName", "ircs-storage-service");
        return worker;
    }

    private RuntimeWorkItem item(String taskType, UUID entityId, int attempt) throws Exception {
        StorageWorkPayload payload = new StorageWorkPayload(entityId, "unit-test", "unit-test");
        String taskId = StorageWorkTypes.AVATAR_SYNC.equals(taskType)
                ? StorageWorkTypes.avatarTaskId(entityId)
                : StorageWorkTypes.coverR2TaskId(entityId);
        return new RuntimeWorkItem(
                taskType,
                taskId,
                UUID.randomUUID().toString(),
                entityId.toString(),
                "unit-test",
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
            return items.stream()
                    .filter(item -> item.taskType().equals(taskType))
                    .toList();
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
