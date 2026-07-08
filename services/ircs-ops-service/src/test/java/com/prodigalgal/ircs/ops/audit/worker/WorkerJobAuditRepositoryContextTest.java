package com.prodigalgal.ircs.ops.audit.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class WorkerJobAuditRepositoryContextTest {

    @Test
    void springContextCreatesRepositoryWithRuntimeConfigConstructor() {
        new ApplicationContextRunner()
                .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(RuntimeConfigService.class, () -> mock(RuntimeConfigService.class))
                .withUserConfiguration(WorkerJobAuditRepositoryConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(WorkerJobAuditRepository.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WorkerJobAuditRepository.class)
    static class WorkerJobAuditRepositoryConfiguration {
    }
}
