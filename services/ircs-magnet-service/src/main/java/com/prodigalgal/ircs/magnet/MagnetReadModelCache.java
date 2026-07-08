package com.prodigalgal.ircs.magnet;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredReadThroughCache;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
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
class MagnetReadModelCache {

    static final String CACHE_PROVIDER_LIST = "magnet.provider.list";
    static final String CACHE_PROVIDER_ENABLED_LIST = "magnet.provider.enabled-list";
    static final String CACHE_PROVIDER_DETAIL = "magnet.provider.detail";
    static final String CACHE_APPROVED_LINKS = "magnet.approved-links";

    private static final String ALL = "all";
    private static final Duration DEFAULT_PROVIDER_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_APPROVED_LINKS_TTL = Duration.ofSeconds(60);

    private final boolean enabledByDeployment;
    private final RuntimeConfigService runtimeConfig;
    private final TieredReadThroughCache<String, List<MagnetProviderSummary>> providerListCache;
    private final TieredReadThroughCache<String, List<MagnetProviderSummary>> enabledProviderListCache;
    private final TieredReadThroughCache<UUID, MagnetProviderSummary> providerDetailCache;
    private final TieredReadThroughCache<UUID, List<MagnetLinkSummary>> approvedLinksCache;
    MagnetReadModelCache(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.magnet.cache.enabled:true}") boolean enabled,
            @Value("${app.magnet.cache.provider-ttl:PT5M}") Duration providerTtl,
            @Value("${app.magnet.cache.approved-links-ttl:PT60S}") Duration approvedLinksTtl) {
        ObjectMapper mapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        CacheRegistry registry = cacheRegistry == null ? new CacheRegistry() : cacheRegistry;
        TieredStringCacheStore store = store(redisTemplate);
        this.enabledByDeployment = enabled;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.providerListCache = register(registry, new TieredReadThroughCache<>(
                CACHE_PROVIDER_LIST,
                ttl(providerTtl, DEFAULT_PROVIDER_TTL),
                "ircs:cache:" + CACHE_PROVIDER_LIST + ":",
                key -> key,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, providerListType(mapper)),
                store));
        this.enabledProviderListCache = register(registry, new TieredReadThroughCache<>(
                CACHE_PROVIDER_ENABLED_LIST,
                ttl(providerTtl, DEFAULT_PROVIDER_TTL),
                "ircs:cache:" + CACHE_PROVIDER_ENABLED_LIST + ":",
                key -> key,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, providerListType(mapper)),
                store));
        this.providerDetailCache = register(registry, new TieredReadThroughCache<>(
                CACHE_PROVIDER_DETAIL,
                ttl(providerTtl, DEFAULT_PROVIDER_TTL),
                "ircs:cache:" + CACHE_PROVIDER_DETAIL + ":",
                UUID::toString,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, MagnetProviderSummary.class),
                store));
        this.approvedLinksCache = register(registry, new TieredReadThroughCache<>(
                CACHE_APPROVED_LINKS,
                ttl(approvedLinksTtl, DEFAULT_APPROVED_LINKS_TTL),
                "ircs:cache:" + CACHE_APPROVED_LINKS + ":",
                UUID::toString,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, linkListType(mapper)),
                store));
    }

    static MagnetReadModelCache disabled() {
        return new MagnetReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                false,
                DEFAULT_PROVIDER_TTL,
                DEFAULT_APPROVED_LINKS_TTL);
    }

    List<MagnetProviderSummary> listProviders(Supplier<List<MagnetProviderSummary>> loader) {
        if (!enabled()) {
            return nullProviders(loader.get());
        }
        return nullProviders(providerListCache.get(ALL, () -> nullProviders(loader.get())));
    }

    Optional<MagnetProviderSummary> findProvider(UUID id, Supplier<Optional<MagnetProviderSummary>> loader) {
        if (id == null) {
            return Optional.empty();
        }
        if (!enabled()) {
            return loader.get();
        }
        MagnetProviderSummary result = providerDetailCache.get(id, () -> loader.get().orElse(null));
        return Optional.ofNullable(result);
    }

    List<MagnetProviderSummary> listEnabledProviders(Supplier<List<MagnetProviderSummary>> loader) {
        if (!enabled()) {
            return nullProviders(loader.get());
        }
        return nullProviders(enabledProviderListCache.get(ALL, () -> nullProviders(loader.get())));
    }

    List<MagnetLinkSummary> findApprovedLinks(UUID unifiedVideoId, Supplier<List<MagnetLinkSummary>> loader) {
        if (unifiedVideoId == null) {
            return List.of();
        }
        if (!enabled()) {
            return nullLinks(loader.get());
        }
        return nullLinks(approvedLinksCache.get(unifiedVideoId, () -> nullLinks(loader.get())));
    }

    void evictProviders() {
        providerListCache.evictAll();
        enabledProviderListCache.evictAll();
        providerDetailCache.evictAll();
    }

    void evictProvider(UUID providerId) {
        providerListCache.evictAll();
        enabledProviderListCache.evictAll();
        if (providerId == null) {
            providerDetailCache.evictAll();
            return;
        }
        providerDetailCache.evictByExternalKey(providerId.toString());
    }

    void evictApprovedLinks(UUID unifiedVideoId) {
        if (unifiedVideoId != null) {
            approvedLinksCache.evictByExternalKey(unifiedVideoId.toString());
        }
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
        return runtimeConfig.booleanValue("app.magnet.cache.enabled", enabledByDeployment);
    }

    private static List<MagnetProviderSummary> nullProviders(List<MagnetProviderSummary> value) {
        return value == null ? List.of() : value;
    }

    private static List<MagnetLinkSummary> nullLinks(List<MagnetLinkSummary> value) {
        return value == null ? List.of() : value;
    }

    private static JavaType providerListType(ObjectMapper mapper) {
        return mapper.getTypeFactory().constructCollectionType(List.class, MagnetProviderSummary.class);
    }

    private static JavaType linkListType(ObjectMapper mapper) {
        return mapper.getTypeFactory().constructCollectionType(List.class, MagnetLinkSummary.class);
    }

    private static Duration ttl(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static TieredStringCacheStore store(ObjectProvider<StringRedisTemplate> redisTemplate) {
        if (redisTemplate == null) {
            return TieredStringCacheStore.noop();
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template == null ? TieredStringCacheStore.noop() : new MagnetRedisStringCacheStore(template);
    }
}
