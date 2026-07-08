package com.prodigalgal.ircs.storage.image;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoverImagePhysicalDeletionService {

    private final CoverImageDeletionRepository coverImageRepository;
    private final LocalObjectStorage localObjectStorage;
    private final R2ObjectStorage r2ObjectStorage;

    @Transactional
    public void delete(UUID imageId) {
        CoverImageRecord image = coverImageRepository.findById(imageId).orElse(null);
        if (image == null) {
            log.warn("Cover image {} not found during storage deletion", imageId);
            return;
        }

        deletePhysicalResources(image);
        int deleted = coverImageRepository.deleteById(imageId);
        log.info("Deleted cover image metadata: id={}, rows={}", imageId, deleted);
    }

    private void deletePhysicalResources(CoverImageRecord image) {
        if (!StringUtils.hasText(image.storagePath())) {
            return;
        }
        if (image.storageType() == CoverImageStorageType.LOCAL
                || (image.storageType() == CoverImageStorageType.R2 && localObjectStorage.exists(image.storagePath()))) {
            localObjectStorage.deleteIfExists(image.storagePath());
        }
        if (image.storageType() == CoverImageStorageType.R2) {
            r2ObjectStorage.delete(image.storagePath());
        }
    }
}
