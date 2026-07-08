package com.prodigalgal.ircs.metadata.provider.douban;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.metadata.provider.credential.MetadataCredentialServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class DoubanProviderContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DoubanProviderConfiguration.class)
            .withPropertyValues(
                    "app.metadata.credential-service.base-url=http://credential-service",
                    "app.metadata.douban.suggest-url=https://movie.douban.com/j/subject_suggest");

    @Test
    void createsDoubanProviderBeansWithProductionConstructors() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DoubanCredentialRepository.class);
            assertThat(context).hasSingleBean(DoubanHttpClient.class);
            assertThat(context).hasSingleBean(DoubanMetadataProvider.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            MetadataCredentialServiceProperties.class,
            DoubanProviderProperties.class
    })
    @Import({
            DoubanCredentialRepository.class,
            RestClientDoubanHttpClient.class,
            DoubanMetadataProvider.class
    })
    static class DoubanProviderConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
