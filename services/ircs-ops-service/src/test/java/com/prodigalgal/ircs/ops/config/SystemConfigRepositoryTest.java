package com.prodigalgal.ircs.ops.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.SystemConfigValkeyCache;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemConfigRepositoryTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider =
            org.mockito.Mockito.mock(ObjectProvider.class);
    private final SystemConfigRepository repository = new SystemConfigRepository(
            jdbcTemplate,
            redisTemplateProvider,
            SystemConfigValkeyCache.DEFAULT_KEY_PREFIX,
            SystemConfigValkeyCache.DEFAULT_TTL,
            Duration.ofHours(1));

    @Test
    void cachesFoundValuesUntilEvicted() {
        when(jdbcTemplate.queryForObject(
                "select config_value from system_configs where config_key = ?",
                String.class,
                OpsConfigValues.REINDEX_DEV_LIMIT_KEY))
                .thenReturn("5")
                .thenReturn("9");

        assertEquals("5", repository.findValue(OpsConfigValues.REINDEX_DEV_LIMIT_KEY).orElseThrow());
        assertEquals("5", repository.findValue(OpsConfigValues.REINDEX_DEV_LIMIT_KEY).orElseThrow());

        repository.evict(OpsConfigValues.REINDEX_DEV_LIMIT_KEY);

        assertEquals("9", repository.findValue(OpsConfigValues.REINDEX_DEV_LIMIT_KEY).orElseThrow());
        org.mockito.Mockito.verify(jdbcTemplate, times(2)).queryForObject(
                "select config_value from system_configs where config_key = ?",
                String.class,
                OpsConfigValues.REINDEX_DEV_LIMIT_KEY);
    }

    @Test
    void cachesMissingValues() {
        when(jdbcTemplate.queryForObject(
                "select config_value from system_configs where config_key = ?",
                String.class,
                "missing"))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertTrue(repository.findValue("missing").isEmpty());
        assertTrue(repository.findValue("missing").isEmpty());

        org.mockito.Mockito.verify(jdbcTemplate, times(1)).queryForObject(
                "select config_value from system_configs where config_key = ?",
                String.class,
                "missing");
    }
}
