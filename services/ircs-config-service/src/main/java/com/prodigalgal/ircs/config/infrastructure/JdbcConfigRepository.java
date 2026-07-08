package com.prodigalgal.ircs.config.infrastructure;

import com.prodigalgal.ircs.common.config.SystemConfigValkeyCache;
import com.prodigalgal.ircs.config.application.SystemConfigDefaults;
import com.prodigalgal.ircs.config.domain.SystemConfigRecord;
import com.prodigalgal.ircs.config.dto.SystemConfigWriteRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConfigRepository {

    private static final int MAX_PAGE_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigValkeyCache valkeyCache;

    public JdbcConfigRepository(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.system-config.valkey-cache.key-prefix:ircs:system-config:v1}") String keyPrefix,
            @Value("${app.system-config.valkey-cache.ttl:PT12H}") Duration ttl,
            @Value("${app.system-config.local-cache.ttl:PT5M}") Duration localTtl) {
        this.jdbcTemplate = jdbcTemplate;
        this.valkeyCache = new SystemConfigValkeyCache(redisTemplateProvider, keyPrefix, ttl, localTtl);
    }

    static JdbcConfigRepository forTest(JdbcTemplate jdbcTemplate) {
        return new JdbcConfigRepository(
                jdbcTemplate,
                null,
                SystemConfigValkeyCache.DEFAULT_KEY_PREFIX,
                SystemConfigValkeyCache.DEFAULT_TTL,
                SystemConfigValkeyCache.DEFAULT_LOCAL_TTL);
    }

    public Page<SystemConfigRecord> findAll(Pageable pageable, String keyword) {
        Pageable safePageable = sanitize(pageable);
        List<Object> whereParams = new ArrayList<>();
        String where = buildKeywordWhere(keyword, whereParams);

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from system_configs " + where,
                Long.class,
                whereParams.toArray());

        List<Object> queryParams = new ArrayList<>(whereParams);
        queryParams.add(safePageable.getPageSize());
        queryParams.add(safePageable.getOffset());

        List<SystemConfigRecord> content = jdbcTemplate.query(
                """
                select id, config_key, config_value, description, coalesce(version, 0) as version, updated_at
                from system_configs
                %s
                order by config_key
                limit ? offset ?
                """.formatted(where),
                mapper(),
                queryParams.toArray());

        return new PageImpl<>(content, safePageable, total == null ? 0 : total);
    }

    public Optional<SystemConfigRecord> findByKey(String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, config_key, config_value, description, coalesce(version, 0) as version, updated_at
                    from system_configs
                    where config_key = ?
                    """,
                    mapper(),
                    key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<String> findValue(String key) {
        return valkeyCache.findValue(key, () -> findValueFromDatabase(key));
    }

    public boolean existsByKey(String key) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from system_configs where config_key = ?)",
                Boolean.class,
                key);
        return Boolean.TRUE.equals(exists);
    }

    public SystemConfigRecord create(SystemConfigWriteRequest request) {
        SystemConfigRecord record = jdbcTemplate.queryForObject(
                """
                insert into system_configs (id, created_at, updated_at, version, config_key, config_value, description)
                values (gen_random_uuid(), now(), now(), 1, ?, ?, ?)
                returning id, config_key, config_value, description, coalesce(version, 0) as version, updated_at
                """,
                mapper(),
                request.key(),
                request.value(),
                request.description());
        if (record != null) {
            valkeyCache.putAfterCommit(record.key(), record.value());
        }
        return record;
    }

    public UpsertResult upsertDefault(SystemConfigDefaults.ResolvedDefault config) {
        List<UpsertRow> rows = jdbcTemplate.query(
                """
                insert into system_configs (id, created_at, updated_at, version, config_key, config_value, description)
                values (gen_random_uuid(), now(), now(), 1, ?, ?, ?)
                on conflict (config_key) do update
                   set config_value = case
                           when (coalesce(btrim(system_configs.config_value), '') = ''
                                   and coalesce(btrim(excluded.config_value), '') <> '')
                                or (? and lower(coalesce(btrim(system_configs.config_value), '')) = 'false')
                                or (cast(? as text) is not null and btrim(system_configs.config_value) = ?)
                           then excluded.config_value
                           else system_configs.config_value
                       end,
                       description = excluded.description,
                       updated_at = case
                           when (coalesce(btrim(system_configs.config_value), '') = ''
                                   and coalesce(btrim(excluded.config_value), '') <> '')
                                or (? and lower(coalesce(btrim(system_configs.config_value), '')) = 'false')
                                or (cast(? as text) is not null and btrim(system_configs.config_value) = ?)
                                or coalesce(system_configs.description, '') is distinct from coalesce(excluded.description, '')
                           then now()
                           else system_configs.updated_at
                       end,
                       version = case
                           when (coalesce(btrim(system_configs.config_value), '') = ''
                                   and coalesce(btrim(excluded.config_value), '') <> '')
                                or (? and lower(coalesce(btrim(system_configs.config_value), '')) = 'false')
                                or (cast(? as text) is not null and btrim(system_configs.config_value) = ?)
                                or coalesce(system_configs.description, '') is distinct from coalesce(excluded.description, '')
                           then coalesce(system_configs.version, 0) + 1
                           else system_configs.version
                       end
                 returning (xmax = 0) as inserted,
                           (xmax <> 0) as description_updated,
                           config_value
                """,
                (rs, rowNum) -> new UpsertRow(
                        rs.getBoolean("inserted"),
                        rs.getBoolean("description_updated"),
                        rs.getString("config_value")),
                config.key(),
                config.value(),
                config.description(),
                config.upgradeLegacyFalse(),
                config.upgradeLegacyValue(),
                config.upgradeLegacyValue(),
                config.upgradeLegacyFalse(),
                config.upgradeLegacyValue(),
                config.upgradeLegacyValue(),
                config.upgradeLegacyFalse(),
                config.upgradeLegacyValue(),
                config.upgradeLegacyValue());
        UpsertRow row = DataAccessUtils.singleResult(rows);
        if (row != null) {
            valkeyCache.putAfterCommit(config.key(), row.value());
        }
        return row == null ? new UpsertResult(false, false) : new UpsertResult(row.inserted(), row.descriptionUpdated());
    }

    public Optional<SystemConfigRecord> update(String key, SystemConfigWriteRequest request) {
        List<SystemConfigRecord> rows = jdbcTemplate.query(
                """
                update system_configs
                   set config_value = ?,
                       description = ?,
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where config_key = ?
                returning id, config_key, config_value, description, coalesce(version, 0) as version, updated_at
                """,
                mapper(),
                request.value(),
                request.description(),
                key);
        SystemConfigRecord record = DataAccessUtils.singleResult(rows);
        if (record != null) {
            valkeyCache.putAfterCommit(record.key(), record.value());
        }
        return Optional.ofNullable(record);
    }

    public int deleteByKey(String key) {
        int deleted = jdbcTemplate.update("delete from system_configs where config_key = ?", key);
        if (deleted > 0) {
            valkeyCache.evictAfterCommit(key);
        }
        return deleted;
    }

    private Optional<String> findValueFromDatabase(String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select config_value from system_configs where config_key = ?",
                    String.class,
                    key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String buildKeywordWhere(String keyword, List<Object> params) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        params.add(pattern);
        params.add(pattern);
        return """
        where lower(config_key) like ?
           or lower(coalesce(description, '')) like ?
        """;
    }

    private Pageable sanitize(Pageable pageable) {
        int page = Math.max(0, pageable.getPageNumber());
        int requestedSize = pageable.isPaged() ? pageable.getPageSize() : 20;
        int size = Math.min(Math.max(1, requestedSize), MAX_PAGE_SIZE);
        return PageRequest.of(page, size);
    }

    private RowMapper<SystemConfigRecord> mapper() {
        return (rs, rowNum) -> new SystemConfigRecord(
                rs.getObject("id", UUID.class),
                rs.getString("config_key"),
                rs.getString("config_value"),
                rs.getString("description"),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private record UpsertRow(boolean inserted, boolean descriptionUpdated, String value) {
    }

    public record UpsertResult(boolean inserted, boolean descriptionUpdated) {
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
