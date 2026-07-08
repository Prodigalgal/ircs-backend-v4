package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.storage.image.AvatarStorageDtos.AvatarUploadResponse;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AvatarStorageService {

    private static final long MAX_AVATAR_SIZE = 2L * 1024 * 1024;

    private final ImageSecurityValidator securityValidator;
    private final FileNormalizationService normalizationService;
    private final LocalObjectStorage localObjectStorage;
    private final StorageConfigValues configValues;

    public AvatarUploadResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "Avatar file exceeds maximum allowed size of 2MB");
        }
        try {
            securityValidator.validateFilename(file.getOriginalFilename());
            NormalizedFile normalized = normalizationService.normalize(
                    file.getBytes(),
                    file.getContentType(),
                    configValues.avatarPathPrefix());
            if (!localObjectStorage.exists(normalized.storageKey())) {
                localObjectStorage.store(normalized.data(), normalized.storageKey(), normalized.mimeType());
            }
            return new AvatarUploadResponse(
                    resolvePublicUrl(normalized.storageKey()),
                    normalized.storageKey(),
                    normalized.mimeType(),
                    normalized.size(),
                    normalized.hash());
        } catch (SecurityException ex) {
            throw new StorageApiException(HttpStatus.BAD_REQUEST, "Security check failed: " + ex.getMessage());
        } catch (IOException ex) {
            throw new StorageApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read avatar file");
        } catch (IllegalStateException ex) {
            throw new StorageApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store avatar file");
        }
    }

    private String resolvePublicUrl(String storageKey) {
        String base = configValues.storagePublicPath().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String cleanKey = storageKey.startsWith("/") ? storageKey.substring(1) : storageKey;
        return base + "/" + cleanKey;
    }
}
