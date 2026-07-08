package com.prodigalgal.ircs.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregationSearchSyncPublisherTest {

    private final SearchSyncWorkPublisher workPublisher = org.mockito.Mockito.mock(SearchSyncWorkPublisher.class);
    private final AggregationSearchSyncPublisher publisher =
            new AggregationSearchSyncPublisher(workPublisher);

    @Test
    void enqueuesRawIndexInSearchWorkQueue() {
        UUID rawVideoId = UUID.randomUUID();

        publisher.publishRawIndex(rawVideoId);

        verify(workPublisher).enqueue(rawVideoId, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX);
        assertThat(rawVideoId).isNotNull();
    }

    @Test
    void enqueuesUnifiedIndexInSearchWorkQueue() {
        UUID unifiedVideoId = UUID.randomUUID();

        publisher.publishUnifiedIndex(unifiedVideoId);

        verify(workPublisher).enqueue(unifiedVideoId, SearchEntityType.UNIFIED_VIDEO, SyncOperation.INDEX);
        assertThat(unifiedVideoId).isNotNull();
    }

    @Test
    void enqueuesUnifiedDeleteInSearchWorkQueue() {
        UUID victimId = UUID.randomUUID();

        publisher.publishUnifiedDelete(victimId);

        verify(workPublisher).enqueue(victimId, SearchEntityType.UNIFIED_VIDEO, SyncOperation.DELETE);
        assertThat(victimId).isNotNull();
    }
}
