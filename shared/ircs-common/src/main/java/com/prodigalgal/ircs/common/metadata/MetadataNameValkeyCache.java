package com.prodigalgal.ircs.common.metadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

public class MetadataNameValkeyCache {

    public static final String DEFAULT_KEY_PREFIX = "ircs:metadata:names:v1";

    private static final Logger log = LoggerFactory.getLogger(MetadataNameValkeyCache.class);
    private static final String EMPTY_SENTINEL = "";
    private static final String DELIMITER = "\u001F";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String keyPrefix;
    private final Duration ttl;

    public MetadataNameValkeyCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            String keyPrefix,
            Duration ttl) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.keyPrefix = hasText(keyPrefix) ? keyPrefix.trim() : DEFAULT_KEY_PREFIX;
        this.ttl = ttl == null ? Duration.ofHours(12) : ttl;
    }

    public Map<UUID, Set<String>> findNames(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Collection<UUID> ownerIds) {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null) {
            return null;
        }
        List<UUID> ids = normalizeIds(ownerIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<Object> fields = ids.stream().map(UUID::toString).map(Object.class::cast).toList();
            List<Object> values = redisTemplate.opsForHash().multiGet(hashKey(ownerType, relation), fields);
            Map<UUID, Set<String>> result = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                Object value = values == null || i >= values.size() ? null : values.get(i);
                if (value != null) {
                    result.put(ids.get(i), deserialize(String.valueOf(value)));
                }
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn("Read metadata name Valkey cache failed: ownerType={}, relation={}", ownerType, relation, ex);
            return null;
        }
    }

    public void putNames(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Map<UUID, Set<String>> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<String>> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                payload.put(entry.getKey().toString(), serialize(entry.getValue()));
            }
        }
        putAll(ownerType, relation, payload);
    }

    public void putEmpty(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Collection<UUID> ownerIds) {
        List<UUID> ids = normalizeIds(ownerIds);
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        for (UUID id : ids) {
            payload.put(id.toString(), EMPTY_SENTINEL);
        }
        putAll(ownerType, relation, payload);
    }

    public void replaceNames(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Map<UUID, Set<String>> values) {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null) {
            return;
        }
        String key = hashKey(ownerType, relation);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Delete metadata name Valkey cache failed: ownerType={}, relation={}", ownerType, relation, ex);
            return;
        }
        putNames(ownerType, relation, values);
    }

    public void evictOwner(MetadataNameOwnerType ownerType, Collection<UUID> ownerIds) {
        for (MetadataNameRelation relation : MetadataNameRelation.values()) {
            evictNames(ownerType, relation, ownerIds);
        }
    }

    public void evictNames(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Collection<UUID> ownerIds) {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null) {
            return;
        }
        List<UUID> ids = normalizeIds(ownerIds);
        if (ids.isEmpty()) {
            return;
        }
        try {
            Object[] fields = ids.stream().map(UUID::toString).toArray();
            redisTemplate.opsForHash().delete(hashKey(ownerType, relation), fields);
        } catch (RuntimeException ex) {
            log.warn("Evict metadata name Valkey cache failed: ownerType={}, relation={}", ownerType, relation, ex);
        }
    }

    public void evictAll() {
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (MetadataNameOwnerType ownerType : MetadataNameOwnerType.values()) {
            for (MetadataNameRelation relation : MetadataNameRelation.values()) {
                keys.add(hashKey(ownerType, relation));
            }
        }
        try {
            redisTemplate.delete(keys);
        } catch (RuntimeException ex) {
            log.warn("Evict all metadata name Valkey caches failed", ex);
        }
    }

    private void putAll(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Map<String, String> payload) {
        if (payload.isEmpty()) {
            return;
        }
        StringRedisTemplate redisTemplate = redisTemplate();
        if (redisTemplate == null) {
            return;
        }
        String key = hashKey(ownerType, relation);
        try {
            redisTemplate.opsForHash().putAll(key, payload);
            if (!ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.expire(key, ttl);
            }
        } catch (RuntimeException ex) {
            log.warn("Write metadata name Valkey cache failed: ownerType={}, relation={}", ownerType, relation, ex);
        }
    }

    private String hashKey(MetadataNameOwnerType ownerType, MetadataNameRelation relation) {
        return keyPrefix + ":" + ownerType.key() + ":" + relation.key();
    }

    private StringRedisTemplate redisTemplate() {
        return redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    private static String serialize(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return EMPTY_SENTINEL;
        }
        List<String> normalized = names.stream()
                .filter(MetadataNameValkeyCache::hasText)
                .map(String::trim)
                .map(value -> value.replace(DELIMITER, " "))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return normalized.isEmpty() ? EMPTY_SENTINEL : String.join(DELIMITER, normalized);
    }

    private static Set<String> deserialize(String value) {
        if (!hasText(value)) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : value.split(DELIMITER, -1)) {
            if (hasText(name)) {
                names.add(name.trim());
            }
        }
        return Set.copyOf(names);
    }

    private static List<UUID> normalizeIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
