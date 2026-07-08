package com.prodigalgal.ircs.credential;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredReadThroughCache;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class CredentialReadModelCache {

    static final String CACHE_SUMMARY_LIST = "credential.summary.list";
    static final String CACHE_SUMMARY_DETAIL = "credential.summary.detail";

    private static final Duration DEFAULT_SUMMARY_TTL = Duration.ofSeconds(60);

    private final boolean enabledByDeployment;
    private final RuntimeConfigService runtimeConfig;
    private final TieredReadThroughCache<ListKey, List<CredentialSummary>> summaryListCache;
    private final TieredReadThroughCache<UUID, CredentialSummary> summaryDetailCache;
    CredentialReadModelCache(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.credential.cache.enabled:true}") boolean enabled,
            @Value("${app.credential.cache.summary-ttl:PT60S}") Duration summaryTtl) {
        ObjectMapper mapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        CacheRegistry registry = cacheRegistry == null ? new CacheRegistry() : cacheRegistry;
        TieredStringCacheStore store = store(redisTemplate);
        this.enabledByDeployment = enabled;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.summaryListCache = register(registry, new TieredReadThroughCache<>(
                CACHE_SUMMARY_LIST,
                ttl(summaryTtl),
                "ircs:cache:" + CACHE_SUMMARY_LIST + ":",
                ListKey::externalKey,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, summaryListType(mapper)),
                store));
        this.summaryDetailCache = register(registry, new TieredReadThroughCache<>(
                CACHE_SUMMARY_DETAIL,
                ttl(summaryTtl),
                "ircs:cache:" + CACHE_SUMMARY_DETAIL + ":",
                UUID::toString,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, CredentialSummary.class),
                store));
    }

    static CredentialReadModelCache disabled() {
        return new CredentialReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                false,
                DEFAULT_SUMMARY_TTL);
    }

    List<CredentialSummary> list(
            String normalizedProvider,
            Boolean enabledFilter,
            int limit,
            Supplier<List<CredentialSummary>> loader) {
        if (!enabled()) {
            return nullToEmpty(loader.get());
        }
        return nullToEmpty(summaryListCache.get(
                new ListKey(normalizedProvider, enabledFilter, limit),
                () -> nullToEmpty(loader.get())));
    }

    Optional<CredentialSummary> findById(UUID id, Supplier<Optional<CredentialSummary>> loader) {
        if (id == null) {
            return Optional.empty();
        }
        if (!enabled()) {
            return loader.get();
        }
        CredentialSummary result = summaryDetailCache.get(id, () -> loader.get().orElse(null));
        return Optional.ofNullable(result);
    }

    void evictAll() {
        summaryListCache.evictAll();
        summaryDetailCache.evictAll();
    }

    private <K, T> TieredReadThroughCache<K, T> register(
            CacheRegistry registry,
            TieredReadThroughCache<K, T> cache) {
        registry.register(cache);
        return cache;
    }

    private boolean enabled() {
        if (runtimeConfig == null) {
            return enabledByDeployment;
        }
        return runtimeConfig.booleanValue("app.credential.cache.enabled", enabledByDeployment);
    }

    private static List<CredentialSummary> nullToEmpty(List<CredentialSummary> value) {
        return value == null ? List.of() : value;
    }

    private static JavaType summaryListType(ObjectMapper mapper) {
        return mapper.getTypeFactory().constructCollectionType(List.class, CredentialSummary.class);
    }

    private static Duration ttl(Duration value) {
        return value == null || value.isZero() || value.isNegative() ? DEFAULT_SUMMARY_TTL : value;
    }

    private static TieredStringCacheStore store(ObjectProvider<StringRedisTemplate> redisTemplate) {
        if (redisTemplate == null) {
            return TieredStringCacheStore.noop();
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template == null ? TieredStringCacheStore.noop() : new CredentialRedisStringCacheStore(template);
    }

    record ListKey(String provider, Boolean enabled, int limit) {

        String externalKey() {
            return "provider=%s|enabled=%s|limit=%d".formatted(
                    token(provider == null ? "*" : provider),
                    enabled == null ? "*" : enabled,
                    limit);
        }

        private String token(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
