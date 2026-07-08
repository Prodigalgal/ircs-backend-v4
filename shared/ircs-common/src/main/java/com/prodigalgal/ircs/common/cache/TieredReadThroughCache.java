package com.prodigalgal.ircs.common.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TieredReadThroughCache<K, V> implements GovernedCache {

    private static final Logger log = LoggerFactory.getLogger(TieredReadThroughCache.class);

    private final TtlGovernedCache<K, V> localCache;
    private final TieredStringCacheStore externalStore;
    private final String externalKeyPrefix;
    private final Function<K, String> externalKeyFormatter;
    private final StringEncoder<V> encoder;
    private final StringDecoder<V> decoder;

    public TieredReadThroughCache(
            String name,
            Duration ttl,
            String externalKeyPrefix,
            Function<K, String> externalKeyFormatter,
            StringEncoder<V> encoder,
            StringDecoder<V> decoder,
            TieredStringCacheStore externalStore) {
        this(name, ttl, Clock.systemUTC(), externalKeyPrefix, externalKeyFormatter, encoder, decoder, externalStore);
    }

    public TieredReadThroughCache(
            String name,
            Duration ttl,
            Clock clock,
            String externalKeyPrefix,
            Function<K, String> externalKeyFormatter,
            StringEncoder<V> encoder,
            StringDecoder<V> decoder,
            TieredStringCacheStore externalStore) {
        this.externalKeyFormatter = Objects.requireNonNull(externalKeyFormatter, "externalKeyFormatter is required");
        this.localCache = new TtlGovernedCache<>(name, ttl, clock, this.externalKeyFormatter);
        this.externalKeyPrefix = normalizePrefix(externalKeyPrefix, name);
        this.encoder = Objects.requireNonNull(encoder, "encoder is required");
        this.decoder = Objects.requireNonNull(decoder, "decoder is required");
        this.externalStore = externalStore == null ? TieredStringCacheStore.noop() : externalStore;
    }

    @Override
    public String name() {
        return localCache.name();
    }

    public V get(K key, Supplier<V> loader) {
        return localCache.get(key, () -> loadExternalOrSource(key, loader));
    }

    @Override
    public long evictAll() {
        long local = localCache.evictAll();
        long external = evictExternalByPrefix();
        return local + external;
    }

    @Override
    public long evictByExternalKey(String externalKey) {
        long local = localCache.evictByExternalKey(externalKey);
        long external = evictExternal(externalKey(externalKey));
        return local + external;
    }

    @Override
    public CacheSummary summary() {
        return localCache.summary();
    }

    private V loadExternalOrSource(K key, Supplier<V> loader) {
        String externalKey = externalKey(key);
        V externalValue = readExternal(externalKey);
        if (externalValue != null) {
            return externalValue;
        }
        V value = loader.get();
        writeExternal(externalKey, value);
        return value;
    }

    private V readExternal(String key) {
        try {
            return externalStore.get(key)
                    .filter(raw -> !raw.isBlank())
                    .map(this::decode)
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Failed to read cache={} key={} from external cache; falling back to loader", name(), key, ex);
            evictExternal(key);
            return null;
        }
    }

    private V decode(String raw) {
        try {
            return decoder.decode(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decode external cache payload", ex);
        }
    }

    private void writeExternal(String key, V value) {
        if (value == null) {
            return;
        }
        try {
            externalStore.set(key, encoder.encode(value), localCache.ttl());
        } catch (Exception ex) {
            log.warn("Failed to write cache={} key={} to external cache; DB remains authoritative", name(), key, ex);
        }
    }

    private long evictExternal(String key) {
        try {
            return externalStore.evict(key);
        } catch (RuntimeException ex) {
            log.warn("Failed to evict cache={} key={} from external cache", name(), key, ex);
            return 0;
        }
    }

    private long evictExternalByPrefix() {
        try {
            return externalStore.evictByPrefix(externalKeyPrefix);
        } catch (RuntimeException ex) {
            log.warn("Failed to evict cache={} prefix={} from external cache", name(), externalKeyPrefix, ex);
            return 0;
        }
    }

    private String externalKey(K key) {
        return externalKey(externalKeyFormatter.apply(key));
    }

    private String externalKey(String externalKey) {
        String normalized = externalKey == null ? "" : externalKey.trim();
        return externalKeyPrefix + normalized;
    }

    private static String normalizePrefix(String prefix, String name) {
        if (prefix != null && !prefix.isBlank()) {
            return prefix.trim();
        }
        return "ircs:cache:" + name + ":";
    }

    @FunctionalInterface
    public interface StringEncoder<V> {
        String encode(V value) throws Exception;
    }

    @FunctionalInterface
    public interface StringDecoder<V> {
        V decode(String value) throws Exception;
    }
}
