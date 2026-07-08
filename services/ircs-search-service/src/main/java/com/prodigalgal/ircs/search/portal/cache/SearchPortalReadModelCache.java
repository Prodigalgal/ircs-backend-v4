package com.prodigalgal.ircs.search.portal.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredReadThroughCache;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.search.portal.dto.PortalMovieCardResponse;
import com.prodigalgal.ircs.search.portal.infrastructure.SearchRedisStringCacheStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SearchPortalReadModelCache {

    static final String CACHE_PORTAL_SUGGEST = "search.portal.suggest.public";
    static final String CACHE_PORTAL_RECOMMEND = "search.portal.recommend.public";

    private static final Duration DEFAULT_SUGGEST_TTL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_RECOMMEND_TTL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_VERSION_REFRESH_INTERVAL = Duration.ofSeconds(2);
    private static final String DEFAULT_PORTAL_PUBLIC_VERSION_KEY = "ircs:cache:search.portal.public.version";

    private final boolean enabledByDeployment;
    private final RuntimeConfigService runtimeConfig;
    private final TieredReadThroughCache<String, List<String>> suggestCache;
    private final TieredReadThroughCache<String, RecommendationPage> recommendCache;
    private final StringRedisTemplate redisTemplate;
    private final String portalPublicVersionKey;
    private final long versionRefreshIntervalNanos;
    private final AtomicLong cachedPortalPublicVersion = new AtomicLong(0L);
    private final AtomicLong versionRefreshAfterNanos = new AtomicLong(0L);

    SearchPortalReadModelCache(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<TieredStringCacheStore> externalStore,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.search.cache.enabled:true}") boolean enabled,
            @Value("${app.search.cache.portal-suggest-ttl:PT60S}") Duration suggestTtl,
            @Value("${app.search.cache.portal-recommend-ttl:PT60S}") Duration recommendTtl,
            @Value("${app.search.cache.portal-public-version-key:" + DEFAULT_PORTAL_PUBLIC_VERSION_KEY + "}") String portalPublicVersionKey,
            @Value("${app.search.cache.portal-version-refresh-interval:PT2S}") Duration versionRefreshInterval) {
        ObjectMapper mapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        CacheRegistry registry = cacheRegistry == null ? new CacheRegistry() : cacheRegistry;
        StringRedisTemplate template = redisTemplate == null ? null : redisTemplate.getIfAvailable();
        TieredStringCacheStore store = store(template, externalStore);
        this.enabledByDeployment = enabled;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.redisTemplate = template;
        this.portalPublicVersionKey = hasText(portalPublicVersionKey)
                ? portalPublicVersionKey.trim()
                : DEFAULT_PORTAL_PUBLIC_VERSION_KEY;
        Duration effectiveRefreshInterval = ttl(versionRefreshInterval, DEFAULT_VERSION_REFRESH_INTERVAL);
        this.versionRefreshIntervalNanos = effectiveRefreshInterval.toNanos();
        this.suggestCache = register(registry, new TieredReadThroughCache<>(
                CACHE_PORTAL_SUGGEST,
                ttl(suggestTtl, DEFAULT_SUGGEST_TTL),
                "ircs:cache:" + CACHE_PORTAL_SUGGEST + ":",
                key -> key,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, stringListType(mapper)),
                store));
        this.recommendCache = register(registry, new TieredReadThroughCache<>(
                CACHE_PORTAL_RECOMMEND,
                ttl(recommendTtl, DEFAULT_RECOMMEND_TTL),
                "ircs:cache:" + CACHE_PORTAL_RECOMMEND + ":",
                key -> key,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, RecommendationPage.class),
                store));
    }

    public static SearchPortalReadModelCache disabled() {
        return new SearchPortalReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                null,
                false,
                DEFAULT_SUGGEST_TTL,
                DEFAULT_RECOMMEND_TTL,
                DEFAULT_PORTAL_PUBLIC_VERSION_KEY,
                DEFAULT_VERSION_REFRESH_INTERVAL);
    }

    public static SearchPortalReadModelCache forTest(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<TieredStringCacheStore> externalStore,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            boolean enabled,
            Duration suggestTtl,
            Duration recommendTtl) {
        return new SearchPortalReadModelCache(
                objectMapper,
                cacheRegistry,
                redisTemplate,
                externalStore,
                runtimeConfigProvider,
                enabled,
                suggestTtl,
                recommendTtl,
                DEFAULT_PORTAL_PUBLIC_VERSION_KEY,
                DEFAULT_VERSION_REFRESH_INTERVAL);
    }

    public List<String> suggest(
            IrcsRequestPrincipal principal,
            String safeKeyword,
            Supplier<List<String>> loader) {
        if (!cacheablePublic(principal) || safeKeyword == null) {
            return loader.get();
        }
        List<String> result = suggestCache.get(publicCacheKey(token(safeKeyword)), loader);
        return result == null ? List.of() : result;
    }

    public Page<PortalMovieCardResponse> recommend(
            IrcsRequestPrincipal principal,
            UUID videoId,
            Pageable pageable,
            Supplier<Page<PortalMovieCardResponse>> loader) {
        if (!cacheablePublic(principal) || videoId == null || pageable == null) {
            return loader.get();
        }
        RecommendationPage cached = recommendCache.get(recommendKey(videoId, pageable), () -> RecommendationPage.from(loader.get()));
        return cached == null ? Page.empty(pageable) : cached.toPage(pageable);
    }

    public void evictPortalPublicReadModel() {
        cachedPortalPublicVersion.incrementAndGet();
        versionRefreshAfterNanos.set(0L);
        StringRedisTemplate template = redisTemplate;
        if (template != null) {
            try {
                Long version = template.opsForValue().increment(portalPublicVersionKey);
                if (version != null) {
                    cachedPortalPublicVersion.set(Math.max(0L, version));
                }
            } catch (RuntimeException ignored) {
                // Local version already moved forward; external keys are short lived and expire naturally.
            }
        }
    }

    private boolean cacheablePublic(IrcsRequestPrincipal principal) {
        return enabled() && (principal == null || principal.isAnonymous());
    }

    private String recommendKey(UUID videoId, Pageable pageable) {
        return publicCacheKey("%s|p=%d|s=%d|sort=%s".formatted(
                videoId,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                token(pageable.getSort().toString())));
    }

    private String publicCacheKey(String key) {
        return "v=" + portalPublicVersion() + "|" + key;
    }

    private long portalPublicVersion() {
        if (!enabled()) {
            return 0L;
        }
        long now = System.nanoTime();
        long refreshAfter = versionRefreshAfterNanos.get();
        if (now < refreshAfter) {
            return cachedPortalPublicVersion.get();
        }
        if (!versionRefreshAfterNanos.compareAndSet(refreshAfter, now + versionRefreshIntervalNanos)) {
            return cachedPortalPublicVersion.get();
        }
        StringRedisTemplate template = redisTemplate;
        if (template == null) {
            return cachedPortalPublicVersion.get();
        }
        try {
            String raw = template.opsForValue().get(portalPublicVersionKey);
            cachedPortalPublicVersion.set(parseLong(raw));
        } catch (RuntimeException ignored) {
            versionRefreshAfterNanos.set(now + versionRefreshIntervalNanos);
        }
        return cachedPortalPublicVersion.get();
    }

    private boolean enabled() {
        if (runtimeConfig == null) {
            return enabledByDeployment;
        }
        return runtimeConfig.booleanValue("app.search.cache.enabled", enabledByDeployment);
    }

    private String token(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }

    private <T> TieredReadThroughCache<String, T> register(
            CacheRegistry registry,
            TieredReadThroughCache<String, T> cache) {
        registry.register(cache);
        return cache;
    }

    private static JavaType stringListType(ObjectMapper mapper) {
        return mapper.getTypeFactory().constructCollectionType(List.class, String.class);
    }

    private static Duration ttl(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static TieredStringCacheStore store(
            StringRedisTemplate redisTemplate,
            ObjectProvider<TieredStringCacheStore> externalStore) {
        if (externalStore != null) {
            TieredStringCacheStore store = externalStore.getIfAvailable();
            if (store != null) {
                return store;
            }
        }
        if (redisTemplate == null) {
            return TieredStringCacheStore.noop();
        }
        return new SearchRedisStringCacheStore(redisTemplate);
    }

    private static long parseLong(String value) {
        if (!hasText(value)) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record RecommendationPage(
            List<PortalMovieCardResponse> content,
            long totalElements,
            int pageNumber,
            int pageSize) {

        static RecommendationPage from(Page<PortalMovieCardResponse> page) {
            if (page == null) {
                return new RecommendationPage(List.of(), 0, 0, 20);
            }
            return new RecommendationPage(
                    page.getContent() == null ? List.of() : page.getContent(),
                    page.getTotalElements(),
                    page.getNumber(),
                    page.getSize());
        }

        Page<PortalMovieCardResponse> toPage(Pageable fallback) {
            Pageable pageable = fallback == null ? org.springframework.data.domain.PageRequest.of(pageNumber, pageSize) : fallback;
            return new PageImpl<>(content == null ? List.of() : content, pageable, totalElements);
        }
    }
}
