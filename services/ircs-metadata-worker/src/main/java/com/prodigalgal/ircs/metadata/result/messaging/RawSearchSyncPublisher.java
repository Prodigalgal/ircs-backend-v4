package com.prodigalgal.ircs.metadata.result.messaging;

import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RawSearchSyncPublisher {

    private final SearchSyncWorkPublisher searchSyncWorkPublisher;

    public void publishIndex(UUID rawVideoId) {
        searchSyncWorkPublisher.enqueue(rawVideoId, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX);
    }
}
