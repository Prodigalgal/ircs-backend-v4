package com.prodigalgal.ircs.common.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class RuntimeConfigService {

    private final ObjectProvider<RuntimeConfigValueSource> valueSourceProvider;
    private final Environment environment;

    public RuntimeConfigService(ObjectProvider<RuntimeConfigValueSource> valueSourceProvider, Environment environment) {
        this.valueSourceProvider = valueSourceProvider;
        this.environment = environment;
    }

    public Optional<String> value(String key, String... aliases) {
        if (!StringUtils.hasText(key)) {
            return Optional.empty();
        }
        List<String> keys = keys(key, aliases);
        List<RuntimeConfigValueSource> valueSources = valueSources();
        Optional<String> databaseValue = keys.stream()
                .map(databaseKey -> findDatabaseValue(databaseKey, valueSources))
                .flatMap(Optional::stream)
                .filter(StringUtils::hasText)
                .findFirst();
        if (databaseValue.isPresent()) {
            return databaseValue;
        }
        return RuntimeInjectedConfig.find(environment, keys);
    }

    public String stringValue(String key, String fallback, String... aliases) {
        return value(key, aliases)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .orElse(fallback);
    }

    public boolean booleanValue(String key, boolean fallback, String... aliases) {
        return value(key, aliases)
                .map(String::trim)
                .map(String::toLowerCase)
                .map(value -> switch (value) {
                    case "true", "1", "yes", "on", "enabled" -> true;
                    case "false", "0", "no", "off", "disabled" -> false;
                    default -> fallback;
                })
                .orElse(fallback);
    }

    public int intValue(String key, int fallback, String... aliases) {
        return value(key, aliases)
                .map(value -> parseInt(key, value, fallback))
                .orElse(fallback);
    }

    public int boundedIntValue(String key, int fallback, int min, int max, String... aliases) {
        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        int value = intValue(key, fallback, aliases);
        return Math.max(lower, Math.min(upper, value));
    }

    public long longValue(String key, long fallback, String... aliases) {
        return value(key, aliases)
                .map(value -> parseLong(key, value, fallback))
                .orElse(fallback);
    }

    public double doubleValue(String key, double fallback, String... aliases) {
        return value(key, aliases)
                .map(value -> parseDouble(key, value, fallback))
                .orElse(fallback);
    }

    public Duration durationValue(String key, Duration fallback, String... aliases) {
        return value(key, aliases)
                .map(value -> parseDuration(key, value, fallback))
                .orElse(fallback);
    }

    public Duration positiveDurationValue(String key, Duration fallback, String... aliases) {
        Duration value = durationValue(key, fallback, aliases);
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    public List<String> listValue(String key, List<String> fallback, String... aliases) {
        return value(key, aliases)
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .toList())
                .filter(values -> !values.isEmpty())
                .orElse(fallback);
    }

    public void evict(String key) {
        valueSources().forEach(valueSource -> evict(valueSource, key));
    }

    private Optional<String> findDatabaseValue(String key, List<RuntimeConfigValueSource> valueSources) {
        for (RuntimeConfigValueSource valueSource : valueSources) {
            try {
                Optional<String> value = valueSource.findValue(key);
                if (value.filter(StringUtils::hasText).isPresent()) {
                    return value;
                }
            } catch (RuntimeException ex) {
                log.debug("Runtime config database lookup failed: key={}, error={}", key, ex.getMessage());
            }
        }
        return Optional.empty();
    }

    private void evict(RuntimeConfigValueSource valueSource, String key) {
        try {
            valueSource.evict(key);
        } catch (RuntimeException ex) {
            log.debug("Runtime config cache eviction failed: key={}, error={}", key, ex.getMessage());
        }
    }

    private List<RuntimeConfigValueSource> valueSources() {
        if (valueSourceProvider == null) {
            return List.of();
        }
        return valueSourceProvider.orderedStream()
                .filter(Objects::nonNull)
                .toList();
    }

    private List<String> keys(String key, String... aliases) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        aliases == null ? java.util.stream.Stream.empty() : Arrays.stream(aliases))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private int parseInt(String key, String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            log.debug("Invalid integer runtime config: key={}, value={}", key, value);
            return fallback;
        }
    }

    private long parseLong(String key, String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            log.debug("Invalid long runtime config: key={}, value={}", key, value);
            return fallback;
        }
    }

    private double parseDouble(String key, String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException ex) {
            log.debug("Invalid double runtime config: key={}, value={}", key, value);
            return fallback;
        }
    }

    private Duration parseDuration(String key, String value, Duration fallback) {
        try {
            return DurationStyle.detectAndParse(value.trim());
        } catch (RuntimeException ex) {
            log.debug("Invalid duration runtime config: key={}, value={}", key, value);
            return fallback;
        }
    }
}
