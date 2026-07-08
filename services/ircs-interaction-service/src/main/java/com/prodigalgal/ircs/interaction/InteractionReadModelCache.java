package com.prodigalgal.ircs.interaction;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredReadThroughCache;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class InteractionReadModelCache {

    static final String CACHE_PUBLIC_FEEDBACK_WALL = "interaction.feedback-wall.public";

    private static final Duration DEFAULT_PUBLIC_FEEDBACK_WALL_TTL = Duration.ofSeconds(60);

    private final boolean enabledByDeployment;
    private final RuntimeConfigService runtimeConfig;
    private final TieredReadThroughCache<String, PageResponse<UserMessageResponse>> publicFeedbackWallCache;

    InteractionReadModelCache(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.interaction.cache.enabled:true}") boolean enabled,
            @Value("${app.interaction.cache.feedback-wall-ttl:PT60S}") Duration feedbackWallTtl) {
        ObjectMapper mapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        CacheRegistry registry = cacheRegistry == null ? new CacheRegistry() : cacheRegistry;
        TieredStringCacheStore store = store(redisTemplate);
        this.enabledByDeployment = enabled;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.publicFeedbackWallCache = register(registry, new TieredReadThroughCache<>(
                CACHE_PUBLIC_FEEDBACK_WALL,
                ttl(feedbackWallTtl, DEFAULT_PUBLIC_FEEDBACK_WALL_TTL),
                "ircs:cache:" + CACHE_PUBLIC_FEEDBACK_WALL + ":",
                key -> key,
                mapper::writeValueAsString,
                raw -> mapper.readValue(raw, feedbackWallType(mapper)),
                store));
    }

    static InteractionReadModelCache disabled() {
        return new InteractionReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                false,
                DEFAULT_PUBLIC_FEEDBACK_WALL_TTL);
    }

    PageResponse<UserMessageResponse> publicFeedbackWall(
            PageBounds bounds,
            Supplier<PageResponse<UserMessageResponse>> loader) {
        if (!enabled() || bounds == null) {
            return loader.get();
        }
        return publicFeedbackWallCache.get(publicFeedbackWallKey(bounds), loader);
    }

    void evictPublicFeedbackWall() {
        publicFeedbackWallCache.evictAll();
    }

    private boolean enabled() {
        if (runtimeConfig == null) {
            return enabledByDeployment;
        }
        return runtimeConfig.booleanValue("app.interaction.cache.enabled", enabledByDeployment);
    }

    private String publicFeedbackWallKey(PageBounds bounds) {
        return "p=%d|s=%d".formatted(bounds.page(), bounds.size());
    }

    private <T> TieredReadThroughCache<String, T> register(
            CacheRegistry registry,
            TieredReadThroughCache<String, T> cache) {
        registry.register(cache);
        return cache;
    }

    private static JavaType feedbackWallType(ObjectMapper mapper) {
        return mapper.getTypeFactory().constructParametricType(PageResponse.class, UserMessageResponse.class);
    }

    private static Duration ttl(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static TieredStringCacheStore store(ObjectProvider<StringRedisTemplate> redisTemplate) {
        if (redisTemplate == null) {
            return TieredStringCacheStore.noop();
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template == null ? TieredStringCacheStore.noop() : new InteractionRedisStringCacheStore(template);
    }
}
