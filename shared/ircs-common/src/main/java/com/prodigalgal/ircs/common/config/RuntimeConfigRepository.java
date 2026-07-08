package com.prodigalgal.ircs.common.config;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@ConditionalOnClass(JdbcTemplate.class)
public class RuntimeConfigRepository extends JdbcSystemConfigRepositorySupport {

    public RuntimeConfigRepository(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.system-config.valkey-cache.key-prefix:ircs:system-config:v1}") String keyPrefix,
            @Value("${app.system-config.valkey-cache.ttl:PT12H}") Duration remoteTtl,
            @Value("${app.runtime-config.local-cache.ttl:PT2S}") Duration localTtl) {
        super(jdbcTemplate, redisTemplateProvider, keyPrefix, remoteTtl, localTtl);
    }

    @Override
    public Optional<String> findValue(String key) {
        if (!StringUtils.hasText(key)) {
            return Optional.empty();
        }
        String normalizedKey = key.trim();
        return super.findValue(normalizedKey);
    }

    @Override
    public void evict(String key) {
        if (StringUtils.hasText(key)) {
            super.evict(key.trim());
        }
    }
}
