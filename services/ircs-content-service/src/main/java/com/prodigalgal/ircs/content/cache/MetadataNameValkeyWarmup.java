package com.prodigalgal.ircs.content.cache;

import com.prodigalgal.ircs.common.metadata.MetadataNameOwnerType;
import com.prodigalgal.ircs.common.metadata.MetadataNameRelation;
import com.prodigalgal.ircs.common.metadata.MetadataNameValkeyCache;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
class MetadataNameValkeyWarmup {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectProvider<MetadataNameValkeyCache> metadataNameCacheProvider;

    @EventListener(ApplicationReadyEvent.class)
    void warmup() {
        Thread.ofVirtual()
                .name("metadata-name-valkey-warmup")
                .start(this::warmupInBackground);
    }

    private void warmupInBackground() {
        MetadataNameValkeyCache metadataNameCache = metadataNameCacheProvider.getIfAvailable();
        if (metadataNameCache == null) {
            log.warn("Metadata name Valkey cache bean is unavailable; warmup skipped");
            return;
        }
        long started = System.nanoTime();
        try {
            Map<UUID, Set<String>> rawActors = queryNameSetById(
                    """
                    select rv.id as owner_id, actor_name.name
                    from raw_videos rv
                    join lateral jsonb_array_elements_text(coalesce(rv.actor_names, '[]'::jsonb)) actor_name(name) on true
                    """);
            Map<UUID, Set<String>> rawDirectors = queryNameSetById(
                    """
                    select rv.id as owner_id, director_name.name
                    from raw_videos rv
                    join lateral jsonb_array_elements_text(coalesce(rv.director_names, '[]'::jsonb)) director_name(name) on true
                    """);
            Map<UUID, Set<String>> rawAreas = queryNameSetById(
                    """
                    select rv.id as owner_id, coalesce(sa.name, area_code.code) as name
                    from raw_videos rv
                    join lateral jsonb_array_elements_text(coalesce(rv.area_codes, '[]'::jsonb)) area_code(code) on true
                    left join standard_areas sa on sa.code = area_code.code
                    """);
            Map<UUID, Set<String>> unifiedActors = queryNameSetById(
                    """
                    select uv.id as owner_id, actor_name.name
                    from unified_videos uv
                    join lateral jsonb_array_elements_text(coalesce(uv.actor_names, '[]'::jsonb)) actor_name(name) on true
                    """);
            Map<UUID, Set<String>> unifiedDirectors = queryNameSetById(
                    """
                    select uv.id as owner_id, director_name.name
                    from unified_videos uv
                    join lateral jsonb_array_elements_text(coalesce(uv.director_names, '[]'::jsonb)) director_name(name) on true
                    """);
            Map<UUID, Set<String>> unifiedAreas = queryNameSetById(
                    """
                    select uv.id as owner_id, coalesce(sa.name, area_code.code) as name
                    from unified_videos uv
                    join lateral jsonb_array_elements_text(coalesce(uv.area_codes, '[]'::jsonb)) area_code(code) on true
                    left join standard_areas sa on sa.code = area_code.code
                    """);

            metadataNameCache.replaceNames(MetadataNameOwnerType.RAW, MetadataNameRelation.ACTORS, rawActors);
            metadataNameCache.replaceNames(MetadataNameOwnerType.RAW, MetadataNameRelation.DIRECTORS, rawDirectors);
            metadataNameCache.replaceNames(MetadataNameOwnerType.RAW, MetadataNameRelation.AREAS, rawAreas);
            metadataNameCache.replaceNames(MetadataNameOwnerType.UNIFIED, MetadataNameRelation.ACTORS, unifiedActors);
            metadataNameCache.replaceNames(MetadataNameOwnerType.UNIFIED, MetadataNameRelation.DIRECTORS, unifiedDirectors);
            metadataNameCache.replaceNames(MetadataNameOwnerType.UNIFIED, MetadataNameRelation.AREAS, unifiedAreas);

            log.info(
                    "Metadata name Valkey cache warmed: rawActors={}, rawDirectors={}, rawAreas={}, "
                            + "unifiedActors={}, unifiedDirectors={}, unifiedAreas={}, elapsedMs={}",
                    rawActors.size(),
                    rawDirectors.size(),
                    rawAreas.size(),
                    unifiedActors.size(),
                    unifiedDirectors.size(),
                    unifiedAreas.size(),
                    Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        } catch (RuntimeException ex) {
            log.warn("Metadata name Valkey cache warmup failed; DB fallback remains available", ex);
        }
    }

    private Map<UUID, Set<String>> queryNameSetById(String sql) {
        Map<UUID, Set<String>> result = new HashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource(),
                rs -> {
                    UUID ownerId = rs.getObject("owner_id", UUID.class);
                    String name = rs.getString("name");
                    if (ownerId != null && StringUtils.hasText(name)) {
                        result.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(name);
                    }
                });
        result.replaceAll((ignored, names) -> Set.copyOf(names));
        return Map.copyOf(result);
    }
}
