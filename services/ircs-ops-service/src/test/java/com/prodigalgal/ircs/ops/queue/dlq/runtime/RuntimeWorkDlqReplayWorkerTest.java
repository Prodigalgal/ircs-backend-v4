package com.prodigalgal.ircs.ops.queue.dlq.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lease.ClusterLeaseService;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RuntimeWorkDlqReplayWorkerTest {

    @Test
    void replaysOnlyOneRuntimeDlqMessagePerRunAndHeartbeatsUnifiedConsumer() throws Exception {
        FakeRuntimeWorkQueue queue = new FakeRuntimeWorkQueue();
        RuntimeWorkDlqService service = new RuntimeWorkDlqService(
                queue,
                org.mockito.Mockito.mock(RuntimeConfigService.class),
                Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC));
        FakeClusterLeaseService leaseService = new FakeClusterLeaseService();
        RuntimeWorkDlqReplayWorker worker = new RuntimeWorkDlqReplayWorker(service, provider(leaseService));
        set(worker, "enabled", true);
        set(worker, "maxReplayAttempts", 3);
        set(worker, "configuredTaskTypes", AggregationWorkTypes.RAW_VIDEO);

        int affected = worker.runOnce();

        assertThat(affected).isEqualTo(1);
        assertThat(queue.requeueRequests).containsExactly(new RequeueRequest(AggregationWorkTypes.RAW_VIDEO, 1, 3));
        assertThat(queue.heartbeatOwners).contains("runtime-dlq-replayer");
        assertThat(leaseService.released).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ClusterLeaseService> provider(ClusterLeaseService value) {
        ObjectProvider<ClusterLeaseService> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class FakeClusterLeaseService implements ClusterLeaseService {
        private boolean released;

        @Override
        public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
            return Optional.of(new ClusterLease(
                    name,
                    ownerId,
                    "token",
                    Instant.parse("2026-06-20T00:00:00Z"),
                    Instant.parse("2026-06-20T00:00:30Z")));
        }

        @Override
        public boolean renew(ClusterLease lease, Duration ttl) {
            return true;
        }

        @Override
        public boolean release(ClusterLease lease) {
            released = true;
            return true;
        }
    }

    private static class FakeRuntimeWorkQueue implements RuntimeWorkQueue {
        private final List<RequeueRequest> requeueRequests = new ArrayList<>();
        private final List<String> heartbeatOwners = new ArrayList<>();

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
            return List.of();
        }

        @Override
        public boolean complete(RuntimeWorkItem item) {
            return true;
        }

        @Override
        public boolean fail(RuntimeWorkItem item, boolean retryable, Duration retryDelay, String reason) {
            return true;
        }

        @Override
        public int requeueExpired(String taskType, int limit) {
            return 0;
        }

        @Override
        public int requeueDlq(String taskType, int limit, int maxReplayAttempts) {
            requeueRequests.add(new RequeueRequest(taskType, limit, maxReplayAttempts));
            return 1;
        }

        @Override
        public RuntimeWorkQueueCounts counts(String taskType) {
            return new RuntimeWorkQueueCounts(0, 0, 1);
        }

        @Override
        public void heartbeatDlqConsumer(String taskType, String ownerId, Duration ttl) {
            heartbeatOwners.add(ownerId);
        }
    }

    private record RequeueRequest(String taskType, int limit, int maxReplayAttempts) {
    }
}
