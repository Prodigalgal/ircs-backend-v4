package com.prodigalgal.ircs.common.config;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcSystemConfigRepositorySupport implements RuntimeConfigValueSource {

    private static final String FIND_VALUE_SQL = "select config_value from system_configs where config_key = ?";

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigValkeyCache valkeyCache;

    protected JdbcSystemConfigRepositorySupport(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            String keyPrefix,
            Duration ttl,
            Duration localTtl) {
        this.jdbcTemplate = jdbcTemplate;
        this.valkeyCache = new SystemConfigValkeyCache(redisTemplateProvider, keyPrefix, ttl, localTtl);
    }

    @Override
    public Optional<String> findValue(String key) {
        return valkeyCache.findValue(key, () -> findValueFromDatabase(key));
    }

    @Override
    public void evict(String key) {
        valkeyCache.evictLocal(key);
    }

    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    protected void putAfterCommit(String key, String value) {
        valkeyCache.putAfterCommit(key, value);
    }

    private Optional<String> findValueFromDatabase(String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_VALUE_SQL, String.class, key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }
}
