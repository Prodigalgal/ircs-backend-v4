package com.prodigalgal.ircs.storage.image;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public final class CoverImageDtos {

    private CoverImageDtos() {
    }

    public record UrlRequest(@NotBlank(message = "URL is required") String url) {
    }

    public record CoverImageResponse(
            UUID id,
            CoverImageStorageType storageType,
            CoverImageStatus status,
            String url,
            String originalUrl,
            String storagePath,
            Long fileSize,
            String mimeType,
            String fileHash,
            String sourceDomainValue,
            Integer retryCount,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {
    }

    record CoverImageRow(
            UUID id,
            CoverImageStorageType storageType,
            CoverImageStatus status,
            String originalUrl,
            String storagePath,
            Long fileSize,
            String mimeType,
            String fileHash,
            UUID sourceDomainId,
            String sourceDomainValue,
            Integer retryCount,
            String lastError,
            Instant nextRetryTime,
            Instant createdAt,
            Instant updatedAt) {
    }

    record NormalizedFile(
            byte[] data,
            String hash,
            String mimeType,
            String extension,
            long size,
            String storageKey) {
    }

    record ExtractedSourceDomain(UUID sourceDomainId, String domainValue, String originalUrl) {
    }
}

