package com.prodigalgal.ircs.scraper;

import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ScraperTrafficConfigValues {

    static final String GLOBAL_SAFETY_FLOOR_KEY = "global.traffic.safety-floor-ms";
    static final String GLOBAL_MAX_WAIT_KEY = "global.traffic.max-wait-ms";
    static final String ENABLED_KEY = "app.scraper.traffic.enabled";
    static final String SOURCE_ENABLED_KEY = "app.scraper.traffic.source-enabled";
    static final String EGRESS_ID_KEY = "app.scraper.traffic.egress-id";
    static final String DEFAULT_GAP_KEY = "app.scraper.traffic.default-gap-ms";
    static final String MAX_WAIT_KEY = "app.scraper.traffic.max-wait";
    static final String TTL_KEY = "app.scraper.traffic.ttl";

    static final int DEFAULT_GLOBAL_SAFETY_FLOOR_MS = 3000;
    static final int DEFAULT_GLOBAL_MAX_WAIT_MS = 120000;

    private final Environment environment;
    private final SystemConfigRepository repository;

    ScraperTrafficConfigValues(Environment environment, SystemConfigRepository repository) {
        this.environment = environment;
        this.repository = repository;
    }

    Duration safetyFloor() {
        return Duration.ofMillis(Math.max(0, intValue(DEFAULT_GLOBAL_SAFETY_FLOOR_MS, GLOBAL_SAFETY_FLOOR_KEY)));
    }

    Duration maxWait(Duration fallback) {
        int configured = Math.max(0, intValue(DEFAULT_GLOBAL_MAX_WAIT_MS, GLOBAL_MAX_WAIT_KEY));
        if (configured > 0) {
            return Duration.ofMillis(configured);
        }
        return fallback == null || fallback.isNegative() ? Duration.ZERO : fallback;
    }

    boolean sourceEnabled(boolean fallback) {
        return booleanValue(fallback, SOURCE_ENABLED_KEY);
    }

    boolean enabled(boolean fallback) {
        return booleanValue(fallback, ENABLED_KEY);
    }

    String egressIdentity(String fallback) {
        String raw = value(EGRESS_ID_KEY);
        return StringUtils.hasText(raw) ? raw.trim() : fallback;
    }

    Duration defaultGap(Duration fallback) {
        int configured = Math.max(0, intValue((int) fallback.toMillis(), DEFAULT_GAP_KEY));
        return configured > 0 ? Duration.ofMillis(configured) : fallback;
    }

    Duration limiterMaxWait(Duration fallback) {
        return durationValue(fallback, MAX_WAIT_KEY);
    }

    Duration ttl(Duration fallback) {
        Duration value = durationValue(fallback, TTL_KEY);
        return value == null || !value.isPositive() ? fallback : value;
    }

    private int intValue(int defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean booleanValue(boolean defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private Duration durationValue(Duration defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    private String value(String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        java.util.Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys)
                .or(() -> repository.findValue(key))
                .orElse(null);
    }
}
