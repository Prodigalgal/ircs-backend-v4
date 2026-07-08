package com.prodigalgal.ircs.metadata.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MetadataWorkerAuditConfigurationTest {

    @Test
    void exposesWorkerJobAuditWriterWithServiceSource() {
        new ApplicationContextRunner()
                .withUserConfiguration(MetadataWorkerAuditConfiguration.class)
                .withPropertyValues(
                        "spring.application.name=ircs-metadata-worker",
                        "spring.datasource.url=jdbc:h2:mem:metadata_worker_audit")
                .run(context -> assertThat(context).hasSingleBean(WorkerJobAuditWriter.class));
    }
}
