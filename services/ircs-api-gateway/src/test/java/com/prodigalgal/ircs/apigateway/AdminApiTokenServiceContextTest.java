package com.prodigalgal.ircs.apigateway;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class AdminApiTokenServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "app.gateway.api-token.cache-ttl=PT30S",
                    "app.gateway.api-token.touch-interval=PT5M");

    @Test
    void springContainerSelectsProductionConstructor() {
        contextRunner.run(context -> assertNotNull(context.getBean(AdminApiTokenService.class)));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AdminApiTokenService.class)
    static class TestConfig {

        @Bean
        AdminApiTokenRepository adminApiTokenRepository() {
            return mock(AdminApiTokenRepository.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
