package com.prodigalgal.ircs.config.job;


import com.prodigalgal.ircs.config.application.SystemConfigDefaults;
import com.prodigalgal.ircs.config.infrastructure.JdbcConfigRepository;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

class SystemConfigInitializerRunnerTest {

    private final JdbcConfigRepository repository = org.mockito.Mockito.mock(JdbcConfigRepository.class);
    private final SystemConfigDefaults defaults = new SystemConfigDefaults(NoOpPasswordEncoder.getInstance());

    @Test
    void upsertsAllDefaultConfigsOnStartup() {
        when(repository.upsertDefault(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JdbcConfigRepository.UpsertResult(true, false));
        SystemConfigInitializerRunner runner = SystemConfigInitializerRunner.forTest(repository, defaults);

        runner.run(new DefaultApplicationArguments());

        assertTrue(defaults.all().size() > 20);
        verify(repository, org.mockito.Mockito.times(defaults.all().size()))
                .upsertDefault(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void seedsDefaultsFromRuntimeInjectedEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.ops.metrics.cache-ttl", "PT9S");
        when(repository.upsertDefault(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JdbcConfigRepository.UpsertResult(true, false));
        SystemConfigInitializerRunner runner = new SystemConfigInitializerRunner(
                repository,
                defaults,
                null,
                environment,
                "ircs-config-service",
                "local-test",
                false,
                java.time.Duration.ofMinutes(10));

        runner.run(new DefaultApplicationArguments());

        ArgumentCaptor<SystemConfigDefaults.ResolvedDefault> captor =
                ArgumentCaptor.forClass(SystemConfigDefaults.ResolvedDefault.class);
        verify(repository, org.mockito.Mockito.times(defaults.all().size())).upsertDefault(captor.capture());
        SystemConfigDefaults.ResolvedDefault metricsCacheTtl = captor.getAllValues().stream()
                .filter(config -> config.key().equals("app.ops.metrics.cache-ttl"))
                .findFirst()
                .orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("PT9S", metricsCacheTtl.value());
    }
}
