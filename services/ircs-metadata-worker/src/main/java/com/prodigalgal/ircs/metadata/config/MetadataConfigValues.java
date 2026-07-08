package com.prodigalgal.ircs.metadata.config;

import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MetadataConfigValues {

    static final String TMDB_ENABLED_KEY = "app.metadata.tmdb.enabled";
    static final String DOUBAN_ENABLED_KEY = "app.metadata.douban.enabled";
    static final String RT_ENABLED_KEY = "app.metadata.rotten-tomatoes.enabled";
    static final boolean DEFAULT_PROVIDER_ENABLED = true;
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofMinutes(5);

    private final Environment environment;
    private final SystemConfigRepository repository;

    MetadataConfigValues(
            Environment environment,
            SystemConfigRepository repository) {
        this.environment = environment;
        this.repository = repository;
    }

    public boolean doubanEnabled() {
        return booleanValue(
                DEFAULT_PROVIDER_ENABLED,
                DOUBAN_ENABLED_KEY,
                "APP_METADATA_DOUBAN_ENABLED",
                "app.metadata.providers.douban-enabled");
    }

    public boolean tmdbEnabled() {
        return booleanValue(
                DEFAULT_PROVIDER_ENABLED,
                TMDB_ENABLED_KEY,
                "APP_METADATA_TMDB_ENABLED",
                "app.metadata.providers.tmdb-enabled");
    }

    public boolean rottenTomatoesEnabled() {
        return booleanValue(
                DEFAULT_PROVIDER_ENABLED,
                RT_ENABLED_KEY,
                "APP_METADATA_RT_ENABLED",
                "app.metadata.providers.rotten-tomatoes-enabled");
    }

    public boolean isProviderEnabled(ProviderType providerType) {
        return switch (providerType) {
            case DOUBAN -> doubanEnabled();
            case TMDB -> tmdbEnabled();
            case ROTTEN_TOMATOES -> rottenTomatoesEnabled();
            default -> false;
        };
    }

    public Duration retryDelay() {
        String raw = value(
                "app.metadata.retry-delay",
                "APP_METADATA_RETRY_DELAY");
        return StringUtils.hasText(raw) ? parseDuration(raw, DEFAULT_RETRY_DELAY) : DEFAULT_RETRY_DELAY;
    }

    private boolean booleanValue(boolean defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private String value(String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys)
                .or(() -> repository.findValue(key))
                .orElse(null);
    }

    private Duration parseDuration(String raw, Duration defaultValue) {
        String trimmed = raw.trim();
        if (!StringUtils.hasText(trimmed)) {
            return defaultValue;
        }
        try {
            return Duration.parse(trimmed);
        } catch (RuntimeException ignored) {
        }
        try {
            return Duration.ofMinutes(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
        }
        try {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(trimmed);
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
