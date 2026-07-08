package com.prodigalgal.ircs.ops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class SystemConfigChangedListenerContextTest {

    @Test
    void springContextCreatesListenerWithSingleConstructor() {
        new ApplicationContextRunner()
                .withBean(SystemConfigRepository.class, () -> mock(SystemConfigRepository.class))
                .withBean(RuntimeConfigService.class, () -> mock(RuntimeConfigService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(SystemConfigChangedListenerConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(SystemConfigChangedListener.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SystemConfigChangedListener.class)
    static class SystemConfigChangedListenerConfiguration {
    }
}
