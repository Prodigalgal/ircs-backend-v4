package com.prodigalgal.ircs.common.lock;

import com.prodigalgal.ircs.common.lease.UnavailableClusterLeaseService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DistributedLockMetrics distributedLockMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new DistributedLockMetrics(meterRegistry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLockManager.class)
    DistributedLockManager distributedLockManager(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            DistributedLockMetrics metrics) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return new UnavailableClusterLeaseService("Redis StringRedisTemplate is unavailable for distributed locks");
        }
        return new RedisDistributedLockManager(template, metrics);
    }

    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "distributedLockHealthIndicator")
    HealthIndicator distributedLockHealthIndicator(DistributedLockManager lockManager) {
        return new DistributedLockHealthIndicator(lockManager);
    }
}
