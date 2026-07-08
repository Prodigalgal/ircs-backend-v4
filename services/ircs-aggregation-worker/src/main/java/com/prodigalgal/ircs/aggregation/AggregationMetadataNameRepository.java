package com.prodigalgal.ircs.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import com.prodigalgal.ircs.common.metadata.MetadataNameOwnerType;
import com.prodigalgal.ircs.common.metadata.MetadataNameRelation;
import com.prodigalgal.ircs.common.metadata.MetadataNameValkeyCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class AggregationMetadataNameRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MetadataNameValkeyCache metadataNameCache;
    private final ObjectMapper objectMapper;

    AggregationMetadataNameRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectProvider<MetadataNameValkeyCache> metadataNameCacheProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataNameCache = metadataNameCacheProvider == null
                ? null
                : metadataNameCacheProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    RawVideoAggregationRecord populateRawMetadata(RawVideoAggregationRecord rawVideo) {
        Map<UUID, Set<String>> actors = findRawActorNames(Set.of(rawVideo.id()));
        Map<UUID, Set<String>> directors = findRawDirectorNames(Set.of(rawVideo.id()));
        Map<UUID, Set<String>> areas = findRawAreaNames(Set.of(rawVideo.id()));
        return rawVideo.withMetadata(
                actors.getOrDefault(rawVideo.id(), Set.of()),
                directors.getOrDefault(rawVideo.id(), Set.of()),
                areas.getOrDefault(rawVideo.id(), Set.of()));
    }

    List<RawVideoAggregationRecord> populateRawMetadata(List<RawVideoAggregationRecord> rawVideos) {
        if (rawVideos == null || rawVideos.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (RawVideoAggregationRecord rawVideo : rawVideos) {
            ids.add(rawVideo.id());
        }
        Map<UUID, Set<String>> actors = findRawActorNames(ids);
        Map<UUID, Set<String>> directors = findRawDirectorNames(ids);
        Map<UUID, Set<String>> areas = findRawAreaNames(ids);
        List<RawVideoAggregationRecord> result = new ArrayList<>(rawVideos.size());
        for (RawVideoAggregationRecord rawVideo : rawVideos) {
            result.add(rawVideo.withMetadata(
                    actors.getOrDefault(rawVideo.id(), Set.of()),
                    directors.getOrDefault(rawVideo.id(), Set.of()),
                    areas.getOrDefault(rawVideo.id(), Set.of())));
        }
        return List.copyOf(result);
    }

    List<UnifiedVideoAggregationCandidate> populateUnifiedMetadata(
            List<UnifiedVideoAggregationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (UnifiedVideoAggregationCandidate candidate : candidates) {
            ids.add(candidate.id());
        }
        Map<UUID, Set<String>> actors = findUnifiedActorNames(ids);
        Map<UUID, Set<String>> directors = findUnifiedDirectorNames(ids);
        Map<UUID, Set<String>> areas = findUnifiedAreaNames(ids);
        List<UnifiedVideoAggregationCandidate> result = new ArrayList<>(candidates.size());
        for (UnifiedVideoAggregationCandidate candidate : candidates) {
            result.add(candidate.withMetadata(
                    actors.getOrDefault(candidate.id(), Set.of()),
                    directors.getOrDefault(candidate.id(), Set.of()),
                    areas.getOrDefault(candidate.id(), Set.of())));
        }
        return List.copyOf(result);
    }

    void evictOwner(MetadataNameOwnerType ownerType, Collection<UUID> ownerIds) {
        if (metadataNameCache != null) {
            metadataNameCache.evictOwner(ownerType, ownerIds);
        }
    }

    void evictAll() {
        if (metadataNameCache != null) {
            metadataNameCache.evictAll();
        }
    }

    private Map<UUID, Set<String>> findRawActorNames(Collection<UUID> ids) {
        return cachedNameSetById(
                MetadataNameOwnerType.RAW,
                MetadataNameRelation.ACTORS,
                ids,
                this::queryRawActorNames);
    }

    private Map<UUID, Set<String>> queryRawActorNames(Collection<UUID> ids) {
        return queryFlatNameSetById("raw_videos", "id", "actor_names", ids);
    }

    private Map<UUID, Set<String>> findRawDirectorNames(Collection<UUID> ids) {
        return cachedNameSetById(
                MetadataNameOwnerType.RAW,
                MetadataNameRelation.DIRECTORS,
                ids,
                this::queryRawDirectorNames);
    }

    private Map<UUID, Set<String>> queryRawDirectorNames(Collection<UUID> ids) {
        return queryFlatNameSetById("raw_videos", "id", "director_names", ids);
    }

    private Map<UUID, Set<String>> findRawAreaNames(Collection<UUID> ids) {
        return cachedNameSetById(
                MetadataNameOwnerType.RAW,
                MetadataNameRelation.AREAS,
                ids,
                this::queryRawAreaNames);
    }

    private Map<UUID, Set<String>> queryRawAreaNames(Collection<UUID> ids) {
        return queryFlatNameSetById("raw_videos", "id", "area_codes", ids);
    }

    private Map<UUID, Set<String>> findUnifiedActorNames(Collection<UUID> ids) {
        return cachedNameSetById(
                MetadataNameOwnerType.UNIFIED,
                MetadataNameRelation.ACTORS,
                ids,
                this::queryUnifiedActorNames);
    }

    private Map<UUID, Set<String>> queryUnifiedActorNames(Collection<UUID> ids) {
        return queryFlatNameSetById("unified_videos", "id", "actor_names", ids);
    }

    private Map<UUID, Set<String>> findUnifiedDirectorNames(Collection<UUID> ids) {
        return cachedNameSetById(
                MetadataNameOwnerType.UNIFIED,
                MetadataNameRelation.DIRECTORS,
                ids,
                this::queryUnifiedDirectorNames);
    }

    private Map<UUID, Set<String>> queryUnifiedDirectorNames(Collection<UUID> ids) {
        return queryFlatNameSetById("unified_videos", "id", "director_names", ids);
    }

    private Map<UUID, Set<String>> findUnifiedAreaNames(Collection<UUID> ids) {
        return cachedNameSetById(
                MetadataNameOwnerType.UNIFIED,
                MetadataNameRelation.AREAS,
                ids,
                this::queryUnifiedAreaNames);
    }

    private Map<UUID, Set<String>> queryUnifiedAreaNames(Collection<UUID> ids) {
        return queryFlatNameSetById("unified_videos", "id", "area_codes", ids);
    }

    private Map<UUID, Set<String>> cachedNameSetById(
            MetadataNameOwnerType ownerType,
            MetadataNameRelation relation,
            Collection<UUID> ids,
            Function<Collection<UUID>, Map<UUID, Set<String>>> loader) {
        List<UUID> normalizedIds = normalizeIds(ids);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        if (metadataNameCache == null) {
            return loader.apply(normalizedIds);
        }

        Map<UUID, Set<String>> cached = metadataNameCache.findNames(ownerType, relation, normalizedIds);
        if (cached == null) {
            return loader.apply(normalizedIds);
        }

        List<UUID> missing = normalizedIds.stream()
                .filter(id -> !cached.containsKey(id))
                .toList();
        Map<UUID, Set<String>> loaded = missing.isEmpty() ? Map.of() : loader.apply(missing);
        if (!loaded.isEmpty()) {
            metadataNameCache.putNames(ownerType, relation, loaded);
        }
        if (!missing.isEmpty()) {
            List<UUID> emptyIds = missing.stream()
                    .filter(id -> !loaded.containsKey(id))
                    .toList();
            metadataNameCache.putEmpty(ownerType, relation, emptyIds);
        }

        Map<UUID, Set<String>> result = new LinkedHashMap<>();
        for (UUID id : normalizedIds) {
            if (cached.containsKey(id)) {
                result.put(id, cached.getOrDefault(id, Set.of()));
            } else if (loaded.containsKey(id)) {
                result.put(id, loaded.getOrDefault(id, Set.of()));
            } else {
                result.put(id, Set.of());
            }
        }
        return result;
    }

    private Map<UUID, Set<String>> queryFlatNameSetById(
            String tableName,
            String idColumn,
            String jsonColumn,
            Collection<UUID> ids) {
        List<UUID> normalizedIds = normalizeIds(ids);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> result = new HashMap<>();
        jdbcTemplate.query(
                """
                select %s as owner_id, cast(%s as varchar) as names
                from %s
                where %s in (:ids)
                """.formatted(idColumn, jsonColumn, tableName, idColumn),
                new MapSqlParameterSource("ids", normalizedIds),
                rs -> {
                    UUID ownerId = rs.getObject("owner_id", UUID.class);
                    Set<String> names = JsonStringArrays.readSet(objectMapper, rs.getString("names"));
                    if (ownerId != null && !names.isEmpty()) {
                        result.put(ownerId, names);
                    }
                });
        return result;
    }

    private List<UUID> normalizeIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
