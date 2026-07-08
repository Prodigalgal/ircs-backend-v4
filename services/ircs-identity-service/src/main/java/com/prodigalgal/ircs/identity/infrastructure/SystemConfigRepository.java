package com.prodigalgal.ircs.identity.infrastructure;

import com.prodigalgal.ircs.common.config.JdbcSystemConfigRepositorySupport;
import com.prodigalgal.ircs.common.config.SystemConfigValkeyCache;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SystemConfigRepository extends JdbcSystemConfigRepositorySupport {

    public SystemConfigRepository(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.system-config.valkey-cache.key-prefix:ircs:system-config:v1}") String keyPrefix,
            @Value("${app.system-config.valkey-cache.ttl:PT12H}") Duration ttl,
            @Value("${app.system-config.local-cache.ttl:PT5M}") Duration localTtl) {
        super(jdbcTemplate, redisTemplateProvider, keyPrefix, ttl, localTtl);
    }

    static SystemConfigRepository forTest(JdbcTemplate jdbcTemplate) {
        return new SystemConfigRepository(
                jdbcTemplate,
                null,
                SystemConfigValkeyCache.DEFAULT_KEY_PREFIX,
                SystemConfigValkeyCache.DEFAULT_TTL,
                SystemConfigValkeyCache.DEFAULT_LOCAL_TTL);
    }

    public long upsertValue(String key, String value) {
        Long revision = jdbcTemplate().queryForObject(
                """
                insert into system_configs (id, created_at, updated_at, version, config_key, config_value)
                values (gen_random_uuid(), now(), now(), 1, ?, ?)
                on conflict (config_key) do update
                   set config_value = excluded.config_value,
                       updated_at = now(),
                       version = coalesce(system_configs.version, 0) + 1
                returning coalesce(version, 0)
                """,
                Long.class,
                key,
                value);
        putAfterCommit(key, value);
        return revision == null ? 0L : revision;
    }
}
