package com.prodigalgal.ircs.search.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskBatchEnqueueRequest;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskEnqueueRequest;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SearchSyncTaskInternalControllerTest {

    private final SearchSyncWorkPublisher publisher = org.mockito.Mockito.mock(SearchSyncWorkPublisher.class);
    private final SearchSyncTaskInternalController controller =
            new SearchSyncTaskInternalController(publisher, new SearchInternalAccessPolicy(), 100);

    @Test
    void enqueuesSingleInternalRequest() {
        UUID id = UUID.randomUUID();
        when(publisher.enqueue(id, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX, "content-service", "trace-1"))
                .thenReturn(1);

        var response = controller.enqueue(
                new SearchSyncTaskEnqueueRequest(
                        id,
                        SearchEntityType.RAW_VIDEO,
                        SyncOperation.INDEX,
                        "content-service",
                        "trace-1"),
                null,
                null,
                null,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().accepted()).isEqualTo(1);
        verify(publisher).enqueue(id, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX, "content-service", "trace-1");
    }

    @Test
    void enqueuesBatchInternalRequest() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(publisher.enqueueBatch(ids, SearchEntityType.UNIFIED_VIDEO, SyncOperation.DELETE, "ops", "trace-2"))
                .thenReturn(2);

        var response = controller.enqueueBatch(
                new SearchSyncTaskBatchEnqueueRequest(
                        ids,
                        SearchEntityType.UNIFIED_VIDEO,
                        SyncOperation.DELETE,
                        null,
                        null),
                "ops",
                null,
                null,
                "trace-2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().accepted()).isEqualTo(2);
    }
}
