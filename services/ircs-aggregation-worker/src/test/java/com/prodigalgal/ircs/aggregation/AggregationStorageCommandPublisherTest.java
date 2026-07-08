package com.prodigalgal.ircs.aggregation;

import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregationStorageCommandPublisherTest {

    private final StorageWorkPublisher storageWorkPublisher = org.mockito.Mockito.mock(StorageWorkPublisher.class);
    private final AggregationStorageCommandPublisher publisher = new AggregationStorageCommandPublisher(storageWorkPublisher);

    @Test
    void enqueuesCoverR2SyncWork() {
        UUID coverImageId = UUID.randomUUID();

        publisher.enqueueCoverR2Sync(coverImageId);

        verify(storageWorkPublisher).enqueueCoverR2Sync(coverImageId, "aggregation-cover-selected");
    }
}
