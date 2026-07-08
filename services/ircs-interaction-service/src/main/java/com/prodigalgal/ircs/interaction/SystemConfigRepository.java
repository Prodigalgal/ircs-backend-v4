package com.prodigalgal.ircs.interaction;

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
}
