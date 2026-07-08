package com.prodigalgal.ircs.catalog;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import com.prodigalgal.ircs.common.cache.TieredReadThroughCache;
import com.prodigalgal.ircs.common.cache.TieredStringCacheStore;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class CatalogReadModelCache {

    static final String CACHE_STANDARD_CATEGORY_SUMMARIES = "catalog.standard-categories.summary";
    static final String CACHE_STANDARD_GENRE_SUMMARIES = "catalog.standard-genres.summary";
    static final String CACHE_STANDARD_AREA_SUMMARIES = "catalog.standard-areas.summary";
    static final String CACHE_STANDARD_LANGUAGE_SUMMARIES = "catalog.standard-languages.summary";
    static final String CACHE_STANDARD_CATEGORY_READS = "catalog.standard-categories.reads";
    static final String CACHE_STANDARD_GENRE_READS = "catalog.standard-genres.reads";
    static final String CACHE_STANDARD_AREA_READS = "catalog.standard-areas.reads";
    static final String CACHE_STANDARD_LANGUAGE_READS = "catalog.standard-languages.reads";

    private static final String ALL_KEY = "all";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final boolean enabledByDeployment;
    private final RuntimeConfigService runtimeConfig;
    private final TieredReadThroughCache<String, List<StandardCategorySummary>> standardCategorySummaries;
    private final TieredReadThroughCache<String, List<StandardGenreSummary>> standardGenreSummaries;
    private final TieredReadThroughCache<String, List<StandardAreaSummary>> standardAreaSummaries;
    private final TieredReadThroughCache<String, List<StandardLanguageSummary>> standardLanguageSummaries;
    private final TieredReadThroughCache<String, List<StandardCategoryRead>> standardCategoryReads;
    private final TieredReadThroughCache<String, List<StandardGenreRead>> standardGenreReads;
    private final TieredReadThroughCache<String, List<StandardAreaRead>> standardAreaReads;
    private final TieredReadThroughCache<String, List<StandardLanguageRead>> standardLanguageReads;

    CatalogReadModelCache(
            ObjectMapper objectMapper,
            CacheRegistry cacheRegistry,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.catalog.cache.enabled:true}") boolean enabled,
            @Value("${app.catalog.cache.standard-dictionary-ttl:PT10M}") Duration standardDictionaryTtl) {
        ObjectMapper mapper = objectMapper == null
                ? JsonMapper.builder().findAndAddModules().build()
                : objectMapper;
        CacheRegistry registry = cacheRegistry == null ? new CacheRegistry() : cacheRegistry;
        TieredStringCacheStore store = store(redisTemplate);
        Duration ttl = ttl(standardDictionaryTtl);
        this.enabledByDeployment = enabled;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.standardCategorySummaries = register(registry, cache(
                mapper,
                CACHE_STANDARD_CATEGORY_SUMMARIES,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardCategorySummary.class),
                store));
        this.standardGenreSummaries = register(registry, cache(
                mapper,
                CACHE_STANDARD_GENRE_SUMMARIES,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardGenreSummary.class),
                store));
        this.standardAreaSummaries = register(registry, cache(
                mapper,
                CACHE_STANDARD_AREA_SUMMARIES,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardAreaSummary.class),
                store));
        this.standardLanguageSummaries = register(registry, cache(
                mapper,
                CACHE_STANDARD_LANGUAGE_SUMMARIES,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardLanguageSummary.class),
                store));
        this.standardCategoryReads = register(registry, cache(
                mapper,
                CACHE_STANDARD_CATEGORY_READS,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardCategoryRead.class),
                store));
        this.standardGenreReads = register(registry, cache(
                mapper,
                CACHE_STANDARD_GENRE_READS,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardGenreRead.class),
                store));
        this.standardAreaReads = register(registry, cache(
                mapper,
                CACHE_STANDARD_AREA_READS,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardAreaRead.class),
                store));
        this.standardLanguageReads = register(registry, cache(
                mapper,
                CACHE_STANDARD_LANGUAGE_READS,
                ttl,
                mapper.getTypeFactory().constructCollectionType(List.class, StandardLanguageRead.class),
                store));
    }

    static CatalogReadModelCache disabled() {
        return new CatalogReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                false,
                DEFAULT_TTL);
    }

    List<StandardCategorySummary> standardCategorySummaries(Supplier<List<StandardCategorySummary>> loader) {
        return get(standardCategorySummaries, loader);
    }

    List<StandardGenreSummary> standardGenreSummaries(Supplier<List<StandardGenreSummary>> loader) {
        return get(standardGenreSummaries, loader);
    }

    List<StandardAreaSummary> standardAreaSummaries(Supplier<List<StandardAreaSummary>> loader) {
        return get(standardAreaSummaries, loader);
    }

    List<StandardLanguageSummary> standardLanguageSummaries(Supplier<List<StandardLanguageSummary>> loader) {
        return get(standardLanguageSummaries, loader);
    }

    List<StandardCategoryRead> standardCategoryReads(Supplier<List<StandardCategoryRead>> loader) {
        return get(standardCategoryReads, loader);
    }

    List<StandardGenreRead> standardGenreReads(Supplier<List<StandardGenreRead>> loader) {
        return get(standardGenreReads, loader);
    }

    List<StandardAreaRead> standardAreaReads(Supplier<List<StandardAreaRead>> loader) {
        return get(standardAreaReads, loader);
    }

    List<StandardLanguageRead> standardLanguageReads(Supplier<List<StandardLanguageRead>> loader) {
        return get(standardLanguageReads, loader);
    }

    void evictStandardCategories() {
        standardCategorySummaries.evictAll();
        standardCategoryReads.evictAll();
    }

    void evictStandardGenres() {
        standardGenreSummaries.evictAll();
        standardGenreReads.evictAll();
    }

    void evictStandardAreas() {
        standardAreaSummaries.evictAll();
        standardAreaReads.evictAll();
    }

    void evictStandardLanguages() {
        standardLanguageSummaries.evictAll();
        standardLanguageReads.evictAll();
    }

    private <T> List<T> get(TieredReadThroughCache<String, List<T>> cache, Supplier<List<T>> loader) {
        if (!enabled()) {
            return loader.get();
        }
        List<T> result = cache.get(ALL_KEY, loader);
        return result == null ? List.of() : result;
    }

    private boolean enabled() {
        if (runtimeConfig == null) {
            return enabledByDeployment;
        }
        return runtimeConfig.booleanValue("app.catalog.cache.enabled", enabledByDeployment);
    }

    private <T> TieredReadThroughCache<String, List<T>> cache(
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

    private <T> TieredReadThroughCache<String, List<T>> register(
            CacheRegistry registry,
            TieredReadThroughCache<String, List<T>> cache) {
        registry.register(cache);
        return cache;
    }

    private static Duration ttl(Duration value) {
        return value == null || value.isZero() || value.isNegative() ? DEFAULT_TTL : value;
    }

    private static TieredStringCacheStore store(ObjectProvider<StringRedisTemplate> redisTemplate) {
        if (redisTemplate == null) {
            return TieredStringCacheStore.noop();
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template == null ? TieredStringCacheStore.noop() : new CatalogRedisStringCacheStore(template);
    }
}
