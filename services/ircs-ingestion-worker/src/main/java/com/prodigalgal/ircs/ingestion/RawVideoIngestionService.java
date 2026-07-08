package com.prodigalgal.ircs.ingestion;

import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RawVideoIngestionService {

    private final RawVideoIngestionRepository repository;
    private final IngestionPublisher publisher;

    @Transactional
    public IngestResult ingest(IngestionItem item) {
        Optional<RawVideoState> existing = repository.findStateBySourceHash(item.video().sourceHash());
        if (existing.isPresent()
                && !item.forceIngest()
                && java.util.Objects.equals(existing.get().dataHash(), item.video().dataHash())) {
            return IngestResult.skipped(existing.get().id());
        }

        UUID rawVideoId = repository.upsertRawVideo(item.video(), existing.map(RawVideoState::id).orElse(null));
        PlaylistSyncMessage syncMessage = new PlaylistSyncMessage(
                rawVideoId,
                item.video().sourceHash(),
                item.video().dataHash(),
                item.video().playlists());
        publisher.publishPlaylistSync(syncMessage);
        publisher.publishNormalize(rawVideoId, item.video().dataHash());
        return IngestResult.persisted(rawVideoId);
    }

    @Transactional
    public PlaylistSyncResult syncPlaylists(PlaylistSyncMessage message) {
        Optional<String> currentDataHash = repository.findDataHashById(message.videoId());
        if (currentDataHash.isEmpty()) {
            return PlaylistSyncResult.missingVideo(message.videoId());
        }
        if (!currentDataHash.get().equals(message.dataHash())) {
            return PlaylistSyncResult.stale(message.videoId());
        }
        int playlistCount = repository.replacePlaylists(message);
        return PlaylistSyncResult.synced(message.videoId(), playlistCount);
    }

    public record IngestResult(UUID rawVideoId, boolean skipped) {
        public static IngestResult persisted(UUID rawVideoId) {
            return new IngestResult(rawVideoId, false);
        }

        public static IngestResult skipped(UUID rawVideoId) {
            return new IngestResult(rawVideoId, true);
        }
    }

    public record PlaylistSyncResult(UUID rawVideoId, String status, int playlistCount) {
        public static PlaylistSyncResult synced(UUID rawVideoId, int playlistCount) {
            return new PlaylistSyncResult(rawVideoId, "SYNCED", playlistCount);
        }

        public static PlaylistSyncResult stale(UUID rawVideoId) {
            return new PlaylistSyncResult(rawVideoId, "STALE", 0);
        }

        public static PlaylistSyncResult missingVideo(UUID rawVideoId) {
            return new PlaylistSyncResult(rawVideoId, "MISSING_VIDEO", 0);
        }
    }
}
