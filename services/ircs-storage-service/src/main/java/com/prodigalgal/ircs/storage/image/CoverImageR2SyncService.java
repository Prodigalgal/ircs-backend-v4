package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoverImageR2SyncService {

    private final CoverImageAdminRepository repository;
    private final LocalObjectStorage localObjectStorage;
    private final R2ObjectStorage r2ObjectStorage;

    @Value("${app.storage.image.max-retries:3}")
    private int maxRetries;

    @Value("${app.storage.image.download.enabled:true}")
    private boolean imageDownloadEnabled;

    @Transactional
    public CoverImageR2SyncResult sync(UUID imageId) {
        if (imageId == null) {
            return CoverImageR2SyncResult.skipped("image id is null");
        }
        if (!r2ObjectStorage.isActive()) {
            return CoverImageR2SyncResult.skipped("r2 inactive");
        }

        Optional<CoverImageRow> optional = repository.findRowById(imageId);
        if (optional.isEmpty()) {
            return CoverImageR2SyncResult.skipped("image missing");
        }
        CoverImageRow row = optional.get();
        if (row.storageType() != CoverImageStorageType.LOCAL
                || (row.status() != CoverImageStatus.LOCAL_STORED && row.status() != CoverImageStatus.UPLOADING)) {
            return CoverImageR2SyncResult.skipped("image not eligible");
        }
        if (!StringUtils.hasText(row.storagePath())) {
            return CoverImageR2SyncResult.skipped("storage path empty");
        }

        if (row.status() == CoverImageStatus.LOCAL_STORED) {
            repository.markUploading(imageId);
        }
        Optional<byte[]> data = localObjectStorage.retrieve(row.storagePath());
        if (data.isEmpty()) {
            repository.markFailed(imageId, "Local file missing for R2 upload", maxRetries);
            return CoverImageR2SyncResult.failed("local file missing");
        }

        try {
            r2ObjectStorage.store(data.get(), row.storagePath(), row.mimeType());
            repository.finalizeUpload(imageId, row.storagePath());
            cleanupTemporaryLocalCopy(row.storagePath());
            log.info("Cover image synced to R2 by runtime queue: {}", imageId);
            return CoverImageR2SyncResult.synced(row.storagePath());
        } catch (RuntimeException ex) {
            repository.markFailed(imageId, "R2 upload failed: " + ex.getMessage(), maxRetries);
            log.warn("Cover image R2 upload failed for {}: {}", imageId, ex.getMessage());
            return CoverImageR2SyncResult.failed("r2 upload failed");
        }
    }

    private void cleanupTemporaryLocalCopy(String storagePath) {
        if (imageDownloadEnabled) {
            return;
        }
        try {
            localObjectStorage.deleteIfExists(storagePath);
            log.debug("Auto cleaned temporary local cover after R2 sync: {}", storagePath);
        } catch (RuntimeException ex) {
            log.warn("Cover image R2 sync kept metadata as R2 but failed to clean local copy {}: {}",
                    storagePath,
                    ex.getMessage());
        }
    }

    public record CoverImageR2SyncResult(boolean synced, boolean failed, String storagePath, String reason) {

        static CoverImageR2SyncResult synced(String storagePath) {
            return new CoverImageR2SyncResult(true, false, storagePath, null);
        }

        static CoverImageR2SyncResult failed(String reason) {
            return new CoverImageR2SyncResult(false, true, null, reason);
        }

        static CoverImageR2SyncResult skipped(String reason) {
            return new CoverImageR2SyncResult(false, false, null, reason);
        }
    }
}
