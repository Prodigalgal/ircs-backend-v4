package com.prodigalgal.ircs.storage.image;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CoverImageUrlResolver {

    private final StorageConfigValues configValues;

    public CoverImageUrlResolver(StorageConfigValues configValues) {
        this.configValues = configValues;
    }

    public String resolve(CoverImageDtos.CoverImageRow image) {
        if (image == null) {
            return null;
        }
        if (image.storageType() == CoverImageStorageType.EXTERNAL) {
            return resolveExternal(image.sourceDomainValue(), image.originalUrl());
        }
        if (StringUtils.hasText(image.storagePath())) {
            return resolveManaged(image.storageType(), image.storagePath());
        }
        return resolveExternal(image.sourceDomainValue(), image.originalUrl());
    }

    private String resolveExternal(String sourceDomain, String originalUrl) {
        if (!StringUtils.hasText(originalUrl)) {
            return null;
        }
        if (StringUtils.hasText(sourceDomain) && !"LOCAL_STORAGE".equals(sourceDomain)) {
            return joinUrlParts(sourceDomain.trim(), originalUrl.trim());
        }
        return originalUrl.trim();
    }

    private String resolveManaged(CoverImageStorageType type, String path) {
        if (type == CoverImageStorageType.R2) {
            String r2PublicDomain = configValues.r2PublicDomain();
            String base = r2PublicDomain.startsWith("http") ? r2PublicDomain : "https://" + r2PublicDomain;
            return joinUrlParts(base, path);
        }
        String storagePublicPath = configValues.storagePublicPath();
        if (StringUtils.hasText(storagePublicPath)) {
            return joinUrlParts(storagePublicPath, path);
        }
        return path;
    }

    private String joinUrlParts(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return cleanBase + "/" + cleanPath;
    }
}
