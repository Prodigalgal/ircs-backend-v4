package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AggregationSearchSyncPublisher {

    private final SearchSyncWorkPublisher searchSyncWorkPublisher;

    public void publishRawIndex(UUID rawVideoId) {
        searchSyncWorkPublisher.enqueue(rawVideoId, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX);
    }

    public void publishUnifiedIndex(UUID unifiedVideoId) {
        searchSyncWorkPublisher.enqueue(unifiedVideoId, SearchEntityType.UNIFIED_VIDEO, SyncOperation.INDEX);
    }

    public void publishUnifiedDelete(UUID unifiedVideoId) {
        searchSyncWorkPublisher.enqueue(unifiedVideoId, SearchEntityType.UNIFIED_VIDEO, SyncOperation.DELETE);
    }
}
