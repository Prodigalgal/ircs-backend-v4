package com.prodigalgal.ircs.metadata.provider.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.metadata.provider.credential.MetadataCredentialServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

class TmdbProviderContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TmdbProviderConfiguration.class)
            .withPropertyValues(
                    "app.metadata.credential-service.base-url=http://credential-service",
                    "app.metadata.tmdb.base-url=https://api.themoviedb.org/3");

    @Test
    void createsTmdbProviderBeansWithProductionConstructors() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TmdbCredentialRepository.class);
            assertThat(context).hasSingleBean(TmdbHttpClient.class);
            assertThat(context).hasSingleBean(TmdbRateLimiter.class);
            assertThat(context).hasSingleBean(TmdbMetadataProvider.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            MetadataCredentialServiceProperties.class,
            TmdbProviderProperties.class
    })
    @Import({
            TmdbCredentialRepository.class,
            RestClientTmdbHttpClient.class,
            TmdbRateLimiter.class,
            TmdbMetadataProvider.class
    })
    static class TmdbProviderConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        DistributedLockManager distributedLockManager() {
            return mock(DistributedLockManager.class);
        }
    }
}
