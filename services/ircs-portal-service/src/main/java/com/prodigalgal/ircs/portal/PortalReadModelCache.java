package com.prodigalgal.ircs.portal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredReadThroughCache;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class PortalReadModelCache {

    static final String CACHE_METADATA = "portal.metadata.public";
    static final String CACHE_HOME = "portal.home.public";
    static final String CACHE_EXPLORE = "portal.explore.public";
    static final String CACHE_DETAIL = "portal.detail.public";

    private static final String PUBLIC_KEY = "public";
    private static final Duration DEFAULT_METADATA_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_HOME_TTL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_EXPLORE_TTL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_DETAIL_TTL = Duration.ofMinutes(15);

    private final boolean enabledByDeployment;
    private final RuntimeConfigService runtimeConfig;
    private final TieredReadThroughCache<String, PortalMetadataResponse> metadataCache;
    private final TieredReadThroughCache<String, PortalHomeResponse> homeCache;
    private final TieredReadThroughCache<String, PageResponse<PortalMovieCard>> exploreCache;
    private final TieredReadThroughCache<String, PortalMovieDetailResponse> detailCache;
    PortalReadModelCache(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.portal.cache.enabled:true}") boolean enabled,
            @Value("${app.portal.cache.metadata-ttl:PT5M}") Duration metadataTtl,
            @Value("${app.portal.cache.home-ttl:PT60S}") Duration homeTtl,
            @Value("${app.portal.cache.explore-ttl:PT60S}") Duration exploreTtl,
            @Value("${app.portal.cache.detail-ttl:PT15M}") Duration detailTtl) {
        ObjectMapper mapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        CacheRegistry registry = cacheRegistry == null ? new CacheRegistry() : cacheRegistry;
        TieredStringCacheStore store = store(redisTemplate);
        this.enabledByDeployment = enabled;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.metadataCache = register(registry, cache(
                mapper,
                CACHE_METADATA,
                ttl(metadataTtl, DEFAULT_METADATA_TTL),
                mapper.constructType(PortalMetadataResponse.class),
                store));
        this.homeCache = register(registry, cache(
                mapper,
                CACHE_HOME,
                ttl(homeTtl, DEFAULT_HOME_TTL),
                mapper.constructType(PortalHomeResponse.class),
                store));
        this.exploreCache = register(registry, cache(
                mapper,
                CACHE_EXPLORE,
                ttl(exploreTtl, DEFAULT_EXPLORE_TTL),
                mapper.getTypeFactory().constructParametricType(PageResponse.class, PortalMovieCard.class),
                store));
        this.detailCache = register(registry, cache(
                mapper,
                CACHE_DETAIL,
                ttl(detailTtl, DEFAULT_DETAIL_TTL),
                mapper.constructType(PortalMovieDetailResponse.class),
                store));
    }

    static PortalReadModelCache disabled() {
        return new PortalReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                false,
                DEFAULT_METADATA_TTL,
                DEFAULT_HOME_TTL,
                DEFAULT_EXPLORE_TTL,
                DEFAULT_DETAIL_TTL);
    }

    PortalMetadataResponse metadata(
            IrcsRequestPrincipal principal,
            Supplier<PortalMetadataResponse> loader) {
        if (!cacheablePublic(principal)) {
            return loader.get();
        }
        return metadataCache.get(PUBLIC_KEY, loader);
    }

    PortalHomeResponse home(
            IrcsRequestPrincipal principal,
            Supplier<PortalHomeResponse> loader) {
        if (!cacheablePublic(principal)) {
            return loader.get();
        }
        return homeCache.get(PUBLIC_KEY, loader);
    }

    PageResponse<PortalMovieCard> explore(
            IrcsRequestPrincipal principal,
            int page,
            int size,
            String keyword,
            String type,
            String genre,
            String area,
            String year,
            String language,
            String sort,
            Supplier<PageResponse<PortalMovieCard>> loader) {
        if (!cacheablePublic(principal) || page != 0) {
            return loader.get();
        }
        return exploreCache.get(exploreKey(page, size, keyword, type, genre, area, year, language, sort), loader);
    }

    Optional<PortalMovieDetailResponse> detail(
            IrcsRequestPrincipal principal,
            UUID id,
            Supplier<Optional<PortalMovieDetailResponse>> loader) {
        if (!cacheablePublic(principal) || id == null) {
            return loader.get();
        }
        PortalMovieDetailResponse value = detailCache.get(id.toString(), () -> loader.get().orElse(null));
        return Optional.ofNullable(value);
    }

    private boolean cacheablePublic(IrcsRequestPrincipal principal) {
        return enabled() && (principal == null || principal.isAnonymous());
    }

    private boolean enabled() {
        if (runtimeConfig == null) {
            return enabledByDeployment;
        }
        return runtimeConfig.booleanValue("app.portal.cache.enabled", enabledByDeployment);
    }

    private String exploreKey(
            int page,
            int size,
            String keyword,
            String type,
            String genre,
            String area,
            String year,
            String language,
            String sort) {
        return "p=%d|s=%d|keyword=%s|type=%s|genre=%s|area=%s|year=%s|lang=%s|sort=%s".formatted(
                page,
                size,
                token(keyword),
                token(type),
                token(genre),
                token(area),
                token(year),
                token(language),
                token(sort));
    }

    private String token(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }

    private <T> TieredReadThroughCache<String, T> cache(
            ObjectMapper mapper,
            String name,
            Duration ttl,
            JavaType type,
            TieredStringCacheStore store) {
        return new TieredReadThroughCache<>(
                name,
                ttl,
                "ircs:cache:" + name + ":",
                key -> key,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, type),
                store);
    }

    private <T> TieredReadThroughCache<String, T> register(
            CacheRegistry registry,
            TieredReadThroughCache<String, T> cache) {
        registry.register(cache);
        return cache;
    }

    private static Duration ttl(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static TieredStringCacheStore store(ObjectProvider<StringRedisTemplate> redisTemplate) {
        if (redisTemplate == null) {
            return TieredStringCacheStore.noop();
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template == null ? TieredStringCacheStore.noop() : new PortalRedisStringCacheStore(template);
    }
}
