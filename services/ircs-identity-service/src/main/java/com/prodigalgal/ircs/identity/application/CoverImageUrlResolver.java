package com.prodigalgal.ircs.identity.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CoverImageUrlResolver {

    private final String r2PublicDomain;

    public CoverImageUrlResolver(@Value("${app.storage.r2-public-domain:}") String r2PublicDomain) {
        this.r2PublicDomain = r2PublicDomain;
    }

    public String resolve(String storageType, String originalUrl, String storagePath, String sourceDomain) {
        if ("EXTERNAL".equals(storageType)) {
            return resolveExternal(sourceDomain, originalUrl);
        }
        if (StringUtils.hasText(storagePath)) {
            return resolveManaged(storageType, storagePath);
        }
        return resolveExternal(sourceDomain, originalUrl);
    }

    private String resolveExternal(String sourceDomain, String originalUrl) {
        if (!StringUtils.hasText(originalUrl)) {
            return null;
        }
        if (StringUtils.hasText(sourceDomain)) {
            return joinUrlParts(sourceDomain.trim(), originalUrl.trim());
        }
        return originalUrl.trim();
    }

    private String resolveManaged(String storageType, String storagePath) {
        if ("R2".equals(storageType) && StringUtils.hasText(r2PublicDomain)) {
            String baseUrl = r2PublicDomain.startsWith("http") ? r2PublicDomain : "https://" + r2PublicDomain;
            return joinUrlParts(baseUrl, storagePath);
        }
        return storagePath;
    }

    private String joinUrlParts(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return cleanBase + "/" + cleanPath;
    }
}
