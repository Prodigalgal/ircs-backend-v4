package com.prodigalgal.ircs.metadata.provider.application;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.outbound.EgressIdentities;
import com.prodigalgal.ircs.metadata.config.SystemConfigRepository;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class MetadataProviderTrafficLimiter {

    private static final String ENABLED_KEY = "app.metadata.public-traffic.enabled";
    private static final String DEFAULT_GAP_MS_KEY = "app.metadata.public-traffic.default-gap-ms";
    private static final String MAX_WAIT_KEY = "app.metadata.public-traffic.max-wait";
    private static final String TTL_KEY = "app.metadata.public-traffic.ttl";
    private static final long SETTINGS_CACHE_TTL_NANOS = Duration.ofSeconds(10).toNanos();

    private final DistributedLockManager lockManager;
    private final SystemConfigRepository systemConfigRepository;
    private final TrafficSettings defaultSettings;
    private final String egressIdentity;
    private volatile TrafficSettings cachedSettings;
    private volatile long cachedSettingsExpiresAtNanos;
    MetadataProviderTrafficLimiter(
            DistributedLockManager lockManager,
            ObjectProvider<SystemConfigRepository> systemConfigRepositoryProvider,
            @Value("${app.metadata.public-traffic.enabled:true}") boolean enabled,
            @Value("${app.metadata.public-traffic.egress-id:${APP_METADATA_TRAFFIC_EGRESS_ID:${IRCS_TRAFFIC_EGRESS_ID:${HOST_IP:${HOSTNAME:unknown}}}}}") String egressIdentity,
            @Value("${app.metadata.public-traffic.default-gap-ms:1000}") long defaultGapMs,
            @Value("${app.metadata.public-traffic.max-wait:PT2M}") Duration maxWait,
            @Value("${app.metadata.public-traffic.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.systemConfigRepository = systemConfigRepositoryProvider == null
                ? null
                : systemConfigRepositoryProvider.getIfAvailable();
        this.egressIdentity = EgressIdentities.sanitize(egressIdentity);
        this.defaultSettings = new TrafficSettings(
                enabled,
                defaultGapMs > 0 ? Duration.ofMillis(defaultGapMs) : Duration.ofSeconds(1),
                maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait,
                ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl);
    }

    public static MetadataProviderTrafficLimiter noop() {
        return new MetadataProviderTrafficLimiter(
                null,
                null,
                false,
                "unknown",
                0,
                Duration.ZERO,
                Duration.ZERO);
    }

    public void acquireProviderSlot(String providerCode) {
        TrafficSettings settings = trafficSettings();
        if (!settings.enabled() || lockManager == null || !StringUtils.hasText(providerCode)) {
            return;
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.PROVIDER_FETCH);
        String key = profile.keyPrefix() + "Metadata:Ip:" + egressIdentity + ":" + EgressIdentities.sanitize(providerCode);
        TimeSliceReservation reservation = lockManager.reserveTimeSlice(new TimeSliceReservationRequest(
                key,
                settings.defaultGap(),
                settings.maxWait(),
                settings.ttl()));
        if (reservation.rejected()) {
            throw new MetadataProviderRetryableException(
                    "PROVIDER_TRAFFIC_SATURATED",
                    "Metadata provider traffic saturated: " + providerCode);
        }
        sleepIfNeeded(reservation.waitTime(), providerCode);
    }

    private TrafficSettings trafficSettings() {
        if (systemConfigRepository == null) {
            return defaultSettings;
        }
        long now = System.nanoTime();
        TrafficSettings settings = cachedSettings;
        if (settings != null && now < cachedSettingsExpiresAtNanos) {
            return settings;
        }
        synchronized (this) {
            settings = cachedSettings;
            if (settings != null && now < cachedSettingsExpiresAtNanos) {
                return settings;
            }
            TrafficSettings refreshed = defaultSettings;
            try {
                refreshed = new TrafficSettings(
                        readConfig(ENABLED_KEY)
                                .map(raw -> parseBoolean(raw, defaultSettings.enabled()))
                                .orElse(defaultSettings.enabled()),
                        readConfig(DEFAULT_GAP_MS_KEY)
                                .map(raw -> parseMillis(raw, defaultSettings.defaultGap()))
                                .orElse(defaultSettings.defaultGap()),
                        readConfig(MAX_WAIT_KEY)
                                .map(raw -> parseDuration(raw, defaultSettings.maxWait()))
                                .orElse(defaultSettings.maxWait()),
                        readConfig(TTL_KEY)
                                .map(raw -> parseDuration(raw, defaultSettings.ttl()))
                                .orElse(defaultSettings.ttl()));
            } catch (RuntimeException ex) {
                log.warn("Unable to read metadata public traffic configs: {}", ex.getMessage());
                refreshed = defaultSettings;
            }
            cachedSettings = refreshed;
            cachedSettingsExpiresAtNanos = now + SETTINGS_CACHE_TTL_NANOS;
            return refreshed;
        }
    }

    private java.util.Optional<String> readConfig(String key) {
        systemConfigRepository.evict(key);
        return systemConfigRepository.findValue(key);
    }

    private void sleepIfNeeded(Duration waitTime, String providerCode) {
        if (waitTime == null || !waitTime.isPositive()) {
            return;
        }
        try {
            Thread.sleep(waitTime.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MetadataProviderRetryableException(
                    "PROVIDER_TRAFFIC_INTERRUPTED",
                    "Interrupted while waiting for metadata provider traffic limit: " + providerCode,
                    ex);
        }
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private static Duration parseMillis(String raw, Duration defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0 ? Duration.ofMillis(millis) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Duration parseDuration(String raw, Duration defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            String value = raw.trim();
            if (value.matches("\\d+")) {
                return Duration.ofMillis(Long.parseLong(value));
            }
            Duration parsed = Duration.parse(value);
            return parsed.isNegative() ? defaultValue : parsed;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private record TrafficSettings(boolean enabled, Duration defaultGap, Duration maxWait, Duration ttl) {
    }
}
