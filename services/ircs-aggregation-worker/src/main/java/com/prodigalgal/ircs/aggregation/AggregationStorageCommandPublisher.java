package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AggregationStorageCommandPublisher {

    private final StorageWorkPublisher storageWorkPublisher;

    public void enqueueCoverR2Sync(UUID coverImageId) {
        storageWorkPublisher.enqueueCoverR2Sync(coverImageId, "aggregation-cover-selected");
    }
}
