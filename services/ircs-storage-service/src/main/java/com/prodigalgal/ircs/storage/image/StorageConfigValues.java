package com.prodigalgal.ircs.storage.image;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class StorageConfigValues {

    static final String STORAGE_PUBLIC_PATH_KEY = "app.storage.public-path";
    static final String R2_PUBLIC_DOMAIN_KEY = "app.storage.r2.public-domain";
    static final String R2_PUBLIC_DOMAIN_ALIAS = "app.storage.r2-public-domain";
    static final String AVATAR_PATH_PREFIX_KEY = "app.storage.path.prefix.avatar";
    static final String AVATAR_PATH_PREFIX_ALIAS = "app.storage.avatar-path-prefix";
    static final String DEFAULT_STORAGE_PUBLIC_PATH = "/media";
    static final String DEFAULT_R2_PUBLIC_DOMAIN = "img.mnnu.eu.org";
    static final String DEFAULT_AVATAR_PATH_PREFIX = "avatars";

    private final Environment environment;
    private final SystemConfigRepository repository;

    StorageConfigValues(Environment environment, SystemConfigRepository repository) {
        this.environment = environment;
        this.repository = repository;
    }

    String storagePublicPath() {
        return value(DEFAULT_STORAGE_PUBLIC_PATH, STORAGE_PUBLIC_PATH_KEY);
    }

    String r2PublicDomain() {
        return value(DEFAULT_R2_PUBLIC_DOMAIN, R2_PUBLIC_DOMAIN_KEY, R2_PUBLIC_DOMAIN_ALIAS);
    }

    String avatarPathPrefix() {
        return value(DEFAULT_AVATAR_PATH_PREFIX, AVATAR_PATH_PREFIX_KEY, AVATAR_PATH_PREFIX_ALIAS);
    }

    private String value(String defaultValue, String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys)
                .or(() -> repository.findValue(key))
                .filter(StringUtils::hasText)
                .orElse(defaultValue);
    }
}
