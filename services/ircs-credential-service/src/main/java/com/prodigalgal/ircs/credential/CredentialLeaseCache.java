package com.prodigalgal.ircs.credential;

import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TtlGovernedCache;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialLeaseCache {

    static final String CACHE_NAME = "credential.provider-leases";

    private final TtlGovernedCache<Key, List<ProviderCredentialLease>> cache;
    private final Clock clock;

    public CredentialLeaseCache(
            CacheRegistry registry,
            @Value("${app.credential.lease-cache.ttl:PT30S}") Duration ttl) {
        Clock clock = Clock.systemUTC();
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.cache = new TtlGovernedCache<>(CACHE_NAME, ttl, clock, Key::externalKey);
        registry.register(cache);
    }

    public List<ProviderCredentialLease> get(Key key, Supplier<List<ProviderCredentialLease>> loader) {
        return cache.get(key, () -> List.copyOf(loader.get()));
    }

    public LeaseWindow newWindow() {
        Instant leasedAt = clock.instant();
        return new LeaseWindow(leasedAt, leasedAt.plus(cache.ttl()));
    }

    public long evictAll() {
        return cache.evictAll();
    }

    public long evictProvider(String provider) {
        String normalized = normalizeProvider(provider);
        if (normalized == null) {
            return evictAll();
        }
        return cache.evictIf(key -> key.provider().equals(normalized));
    }

    public record LeaseWindow(Instant leasedAt, Instant expiresAt) {
    }

    public record Key(String provider, String requiredPayloadKey, int limit) {

        public Key {
            provider = normalizeProvider(provider);
            requiredPayloadKey = normalizePayloadKey(requiredPayloadKey);
            if (provider == null) {
                throw new IllegalArgumentException("provider is required");
            }
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be >= 0");
            }
        }

        String externalKey() {
            return provider + "|" + (requiredPayloadKey == null ? "*" : requiredPayloadKey) + "|" + limit;
        }
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return provider.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePayloadKey(String payloadKey) {
        if (payloadKey == null || payloadKey.isBlank()) {
            return null;
        }
        return payloadKey.trim();
    }
}
