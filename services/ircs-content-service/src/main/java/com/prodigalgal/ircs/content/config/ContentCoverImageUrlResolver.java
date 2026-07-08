package com.prodigalgal.ircs.content.config;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ContentCoverImageUrlResolver {

    private static final String R2_PUBLIC_DOMAIN_KEY = "app.storage.r2.public-domain";
    private static final String R2_PUBLIC_DOMAIN_ALIAS = "app.storage.r2-public-domain";
    private static final String STORAGE_PUBLIC_PATH_KEY = "app.storage.public-path";
    private static final String DEFAULT_R2_PUBLIC_DOMAIN = "img.mnnu.eu.org";
    private static final String DEFAULT_STORAGE_PUBLIC_PATH = "/media";

    private final Environment environment;
    private final SystemConfigRepository configRepository;

    ContentCoverImageUrlResolver(Environment environment, SystemConfigRepository configRepository) {
        this.environment = environment;
        this.configRepository = configRepository;
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
        if (StringUtils.hasText(sourceDomain) && !isAbsoluteUrl(originalUrl)) {
            return joinUrlParts(sourceDomain.trim(), originalUrl.trim());
        }
        return originalUrl.trim();
    }

    private String resolveManaged(String storageType, String storagePath) {
        if ("R2".equals(storageType)) {
            String r2PublicDomain = configValue(
                    DEFAULT_R2_PUBLIC_DOMAIN,
                    R2_PUBLIC_DOMAIN_KEY,
                    R2_PUBLIC_DOMAIN_ALIAS);
            String baseUrl = r2PublicDomain.startsWith("http") ? r2PublicDomain : "https://" + r2PublicDomain;
            return joinUrlParts(baseUrl, storagePath);
        }

        String publicPath = configValue(DEFAULT_STORAGE_PUBLIC_PATH, STORAGE_PUBLIC_PATH_KEY);
        if (!StringUtils.hasText(publicPath)) {
            return storagePath;
        }
        return joinUrlParts(publicPath, storagePath);
    }

    private String configValue(String defaultValue, String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys)
                .or(() -> configRepository.findValue(key))
                .filter(StringUtils::hasText)
                .orElse(defaultValue);
    }

    private boolean isAbsoluteUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String joinUrlParts(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return cleanBase + "/" + cleanPath;
    }
}
