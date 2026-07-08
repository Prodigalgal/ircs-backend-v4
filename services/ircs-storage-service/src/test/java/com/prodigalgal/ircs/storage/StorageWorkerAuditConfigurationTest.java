package com.prodigalgal.ircs.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class StorageWorkerAuditConfigurationTest {

    @Test
    void exposesWorkerJobAuditWriterWithServiceSource() {
        new ApplicationContextRunner()
                .withUserConfiguration(StorageServiceAuditConfiguration.class)
                .withBean(JdbcTemplate.class, () -> org.mockito.Mockito.mock(JdbcTemplate.class))
                .withPropertyValues(
                        "spring.application.name=ircs-storage-service",
                        "spring.datasource.url=jdbc:h2:mem:storage_worker_audit")
                .run(context -> assertThat(context).hasSingleBean(WorkerJobAuditWriter.class));
    }
}
