package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageResponse;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoverImageAdminService {

    private final CoverImageAdminRepository repository;
    private final ImageSecurityValidator securityValidator;
    private final FileNormalizationService normalizationService;
    private final LocalObjectStorage localObjectStorage;
    private final R2ObjectStorage r2ObjectStorage;
    private final StorageCommandPublisher publisher;
    private final StorageWorkPublisher storageWorkPublisher;

    @Value("${app.storage.cover-path-prefix:covers}")
    private String coverPathPrefix;

    @Value("${app.storage.image.max-upload-bytes:10485760}")
    private long maxUploadBytes;

    public Page<CoverImageResponse> findAll(
            Pageable pageable,
            CoverImageStatus status,
            CoverImageStorageType storageType,
            String url,
            String sourceDomain,
            Long minFileSize,
            Long maxFileSize) {
        return repository.findAll(pageable, status, storageType, url, sourceDomain, minFileSize, maxFileSize);
    }

    public Optional<CoverImageResponse> findOne(UUID id) {
        return repository.findResponseById(id);
    }

    @Transactional
    public void delete(UUID id) {
        CoverImageRow row = repository.findRowById(id).orElse(null);
        if (row == null || row.status() == CoverImageStatus.PENDING_DELETE) {
            return;
        }
        repository.markPendingDelete(id);
        publisher.publishImageUnlink(id);
    }

    @Transactional
    public CoverImageResponse manualUpload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "File is too large");
        }
        try {
            securityValidator.validateFilename(file.getOriginalFilename());
            NormalizedFile normalized = normalizationService.normalize(file.getBytes(), file.getContentType(), coverPathPrefix);
            if (!localObjectStorage.exists(normalized.storageKey())) {
                localObjectStorage.store(normalized.data(), normalized.storageKey(), normalized.mimeType());
            }
            CoverImageResponse response = repository.createLocal(normalized);
            if (r2ObjectStorage.isActive()) {
                storageWorkPublisher.enqueueCoverR2Sync(response.id(), "cover-manual-upload");
            }
            return response;
        } catch (SecurityException ex) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "Security check failed: " + ex.getMessage());
        } catch (Exception ex) {
            throw new StorageApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save image metadata");
        }
    }

    @Transactional
    public CoverImageResponse createFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "URL cannot be empty");
        }
        CoverImageRow row = repository.getOrCreateExternalReference(url);
        queueDownloadIfEligible(row.id());
        return repository.findResponseById(row.id()).orElseThrow();
    }

    @Transactional
    public void triggerR2Sync(UUID id) {
        if (!r2ObjectStorage.isActive()) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "R2 storage is not enabled");
        }
        CoverImageRow row = repository.findRowById(id)
                .orElseThrow(() -> new StorageApiException(HttpStatus.NOT_FOUND, "Image not found: " + id));
        if (row.storageType() != CoverImageStorageType.LOCAL || row.status() != CoverImageStatus.LOCAL_STORED) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "Image is not in a state eligible for R2 sync");
        }
        storageWorkPublisher.enqueueCoverR2Sync(id, "cover-manual-sync");
        log.info("Queued R2 sync for cover image: {}", id);
    }

    @Transactional
    public void retryDownload(UUID id) {
        CoverImageRow row = repository.findRowById(id)
                .orElseThrow(() -> new StorageApiException(HttpStatus.NOT_FOUND, "Image not found: " + id));
        if (row.status() != CoverImageStatus.FAILED
                && row.status() != CoverImageStatus.DEAD
                && row.status() != CoverImageStatus.UNPROCESSED) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "Image is not in a failed state");
        }
        repository.resetForDownload(id);
        publisher.publishImageDownload(id);
    }

    int enqueueDownloadBackfill(int limit) {
        int count = 0;
        for (UUID id : repository.findDownloadCandidates(limit)) {
            queueDownloadIfEligible(id);
            count++;
        }
        return count;
    }

    private void queueDownloadIfEligible(UUID id) {
        if (id != null) {
            publisher.publishImageDownload(id);
        }
    }
}
