package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class MagnetTrafficConfigValues {

    static final String ENABLED_KEY = "app.magnet.traffic.enabled";
    static final String EGRESS_ID_KEY = "app.magnet.traffic.egress-id";
    static final String DEFAULT_GAP_KEY = "app.magnet.traffic.default-gap-ms";
    static final String PROVIDER_GAP_KEY = "app.magnet.traffic.provider-gap-ms";
    static final String MAX_WAIT_KEY = "app.magnet.traffic.max-wait";
    static final String TTL_KEY = "app.magnet.traffic.ttl";

    private final RuntimeConfigService runtimeConfig;

    MagnetTrafficConfigValues(ObjectProvider<RuntimeConfigService> runtimeConfigProvider) {
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
    }

    boolean enabled(boolean fallback) {
        return runtimeConfig == null ? fallback : runtimeConfig.booleanValue(ENABLED_KEY, fallback);
    }

    String egressIdentity(String fallback) {
        return runtimeConfig == null ? fallback : runtimeConfig.stringValue(EGRESS_ID_KEY, fallback);
    }

    Duration defaultGap(Duration fallback) {
        long configured = runtimeConfig == null
                ? fallback.toMillis()
                : runtimeConfig.longValue(DEFAULT_GAP_KEY, fallback.toMillis());
        return configured > 0 ? Duration.ofMillis(configured) : fallback;
    }

    Duration providerGap(MagnetProviderSummary provider, String deploymentFallback) {
        String raw = runtimeConfig == null
                ? deploymentFallback
                : runtimeConfig.stringValue(PROVIDER_GAP_KEY, deploymentFallback);
        return configuredProviderGap(provider, raw);
    }

    Duration limiterMaxWait(Duration fallback) {
        Duration value = runtimeConfig == null
                ? fallback
                : runtimeConfig.durationValue(MAX_WAIT_KEY, fallback);
        return value == null || value.isNegative() ? fallback : value;
    }

    Duration ttl(Duration fallback) {
        Duration value = runtimeConfig == null
                ? fallback
                : runtimeConfig.durationValue(TTL_KEY, fallback);
        return value == null || !value.isPositive() ? fallback : value;
    }

    static Duration configuredProviderGap(MagnetProviderSummary provider, String raw) {
        if (provider == null) {
            return null;
        }
        Map<String, Long> gaps = providerGaps(raw);
        for (String key : providerKeys(provider)) {
            Long gapMs = gaps.get(key);
            if (gapMs != null && gapMs > 0) {
                return Duration.ofMillis(gapMs);
            }
        }
        return null;
    }

    static Map<String, Long> providerGaps(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        Map<String, Long> gaps = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            String key = normalizeKey(entry.substring(0, separator));
            Long value = parsePositiveLong(entry.substring(separator + 1));
            if (StringUtils.hasText(key) && value != null) {
                gaps.put(key, value);
            }
        }
        return Map.copyOf(gaps);
    }

    private static List<String> providerKeys(MagnetProviderSummary provider) {
        return List.of(
                normalizeKey(provider.code()),
                normalizeKey(provider.providerType()));
    }

    private static String normalizeKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
    }

    private static Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
