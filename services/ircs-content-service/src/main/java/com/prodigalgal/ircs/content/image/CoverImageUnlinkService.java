package com.prodigalgal.ircs.content.image;

import com.prodigalgal.ircs.content.video.messaging.ContentCommandPublisher;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoverImageUnlinkService {

    private final CoverImageReferenceRepository referenceRepository;
    private final CoverImageUnlinkedPublisher unlinkedPublisher;
    private final ContentCommandPublisher searchPublisher;

    @Transactional
    public CoverImageUnlinkResult unlink(UUID imageId) {
        List<UUID> rawVideoIds = referenceRepository.findRawVideoIds(imageId);
        List<UUID> unifiedVideoIds = referenceRepository.findUnifiedVideoIds(imageId);
        int rawVideoCount = referenceRepository.unlinkRawVideos(imageId);
        int unifiedVideoCount = referenceRepository.unlinkUnifiedVideos(imageId);

        log.info(
                "Unlinked cover image {} from {} raw videos and {} unified videos",
                imageId,
                rawVideoCount,
                unifiedVideoCount);
        publishAfterCommit(() -> {
            log.info(
                    "Publishing search refresh after cover image unlink: imageId={}, rawIds={}, unifiedIds={}",
                    imageId,
                    rawVideoIds,
                    unifiedVideoIds);
            rawVideoIds.forEach(id -> {
                searchPublisher.publishRawSearch(id, SyncOperation.DELETE);
                searchPublisher.publishRawSearch(id, SyncOperation.INDEX);
            });
            unifiedVideoIds.forEach(id -> {
                searchPublisher.publishUnifiedSearch(id, SyncOperation.DELETE);
                searchPublisher.publishUnifiedSearch(id, SyncOperation.INDEX);
            });
            unlinkedPublisher.publish(imageId);
        });
        return new CoverImageUnlinkResult(imageId, rawVideoCount, unifiedVideoCount);
    }

    private void publishAfterCommit(Runnable publisher) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.run();
            }
        });
    }
}
