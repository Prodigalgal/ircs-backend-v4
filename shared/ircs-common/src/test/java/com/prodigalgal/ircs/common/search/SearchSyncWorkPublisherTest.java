package com.prodigalgal.ircs.common.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.work.RuntimeWorkItem;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueueCounts;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchSyncWorkPublisherTest {

    @Test
    void enqueuesDistinctRuntimeWorkItems() throws Exception {
        CapturingRuntimeWorkQueue queue = new CapturingRuntimeWorkQueue();
        SearchSyncWorkPublisher publisher = new SearchSyncWorkPublisher(
                queue,
                new ObjectMapper(),
                "content-service");
        UUID id = UUID.randomUUID();

        int accepted = publisher.enqueueBatch(
                List.of(id, id),
                SearchEntityType.RAW_VIDEO,
                SyncOperation.INDEX,
                "content-service",
                "trace-1");

        assertThat(accepted).isEqualTo(1);
        RuntimeWorkItemRequest request = queue.requests.getFirst();
        assertThat(request.taskType()).isEqualTo(SearchSyncWorkTypes.RAW);
        assertThat(request.taskId()).isEqualTo("raw_video:" + id);
        assertThat(request.aggregateId()).isEqualTo(id.toString());
        assertThat(request.version()).isEqualTo("INDEX");
        SearchSyncWorkPayload payload = new ObjectMapper().readValue(request.payload(), SearchSyncWorkPayload.class);
        assertThat(payload.entityId()).isEqualTo(id);
        assertThat(payload.entityType()).isEqualTo(SearchEntityType.RAW_VIDEO);
        assertThat(payload.operation()).isEqualTo(SyncOperation.INDEX);
        assertThat(payload.sourceService()).isEqualTo("content-service");
        assertThat(payload.correlationId()).isEqualTo("trace-1");
    }

    private static class CapturingRuntimeWorkQueue implements RuntimeWorkQueue {
        private final List<RuntimeWorkItemRequest> requests = new ArrayList<>();

        @Override
        public void submit(RuntimeWorkItemRequest request) {
            requests.add(request);
        }

        @Override
        public void submit(RuntimeWorkItemRequest request, Duration delay) {
            requests.add(request);
        }

        @Override
        public void submitAfterCommit(RuntimeWorkItemRequest request) {
            requests.add(request);
        }

        @Override
        public void submitAfterCommit(RuntimeWorkItemRequest request, Duration delay) {
            requests.add(request);
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
        public RuntimeWorkQueueCounts counts(String taskType) {
            return new RuntimeWorkQueueCounts(0, 0, 0);
        }
    }
}
