package com.prodigalgal.ircs.metadata.provider.rt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.metadata.provider.credential.MetadataCredentialServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RottenTomatoesProviderContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RottenTomatoesProviderConfiguration.class)
            .withPropertyValues(
                    "app.metadata.credential-service.base-url=http://credential-service",
                    "app.metadata.rt.search-url=https://www.rottentomatoes.com/search");

    @Test
    void createsRtProviderBeansWithProductionConstructors() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RottenTomatoesCredentialRepository.class);
            assertThat(context).hasSingleBean(RottenTomatoesHttpClient.class);
            assertThat(context).hasSingleBean(RottenTomatoesMetadataProvider.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            MetadataCredentialServiceProperties.class,
            RottenTomatoesProviderProperties.class
    })
    @Import({
            RottenTomatoesCredentialRepository.class,
            RestClientRottenTomatoesHttpClient.class,
            RottenTomatoesMetadataProvider.class
    })
    static class RottenTomatoesProviderConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
