package com.prodigalgal.ircs.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.ingestion.IngestionVideoDTO;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawVideoIngestionServiceTest {

    private final RawVideoIngestionRepository repository = org.mockito.Mockito.mock(RawVideoIngestionRepository.class);
    private final IngestionPublisher publisher = org.mockito.Mockito.mock(IngestionPublisher.class);
    private final RawVideoIngestionService service = new RawVideoIngestionService(repository, publisher);

    @Test
    void skipsUnchangedPayloadWhenForceIngestIsFalse() {
        UUID rawVideoId = UUID.randomUUID();
        IngestionItem item = new IngestionItem(video("source-hash", "data-hash"), false);
        when(repository.findStateBySourceHash("source-hash"))
                .thenReturn(Optional.of(new RawVideoState(rawVideoId, "data-hash")));

        RawVideoIngestionService.IngestResult result = service.ingest(item);

        assertTrue(result.skipped());
        verify(repository, never()).upsertRawVideo(item.video(), rawVideoId);
        verify(publisher, never()).publishNormalize(rawVideoId, "data-hash");
    }

    @Test
    void upsertsChangedPayloadAndPublishesDownstreamMessages() {
        UUID rawVideoId = UUID.randomUUID();
        IngestionItem item = new IngestionItem(video("source-hash", "new-data-hash"), false);
        when(repository.findStateBySourceHash("source-hash"))
                .thenReturn(Optional.of(new RawVideoState(rawVideoId, "old-data-hash")));
        when(repository.upsertRawVideo(item.video(), rawVideoId)).thenReturn(rawVideoId);

        RawVideoIngestionService.IngestResult result = service.ingest(item);

        assertFalse(result.skipped());
        verify(repository).upsertRawVideo(item.video(), rawVideoId);
        verify(publisher).publishNormalize(rawVideoId, "new-data-hash");
    }

    private IngestionVideoDTO video(String sourceHash, String dataHash) {
        return new IngestionVideoDTO(
                "source-vid",
                sourceHash,
                dataHash,
                "Codex Smoke",
                null,
                null,
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "{}",
                "PENDING",
                null,
                List.of(),
                0);
    }
}
