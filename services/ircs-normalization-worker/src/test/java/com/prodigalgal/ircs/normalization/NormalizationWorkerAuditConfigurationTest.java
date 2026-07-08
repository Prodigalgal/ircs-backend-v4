package com.prodigalgal.ircs.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NormalizationWorkerAuditConfigurationTest {

    @Test
    void exposesWorkerJobAuditWriterWithServiceSource() {
        new ApplicationContextRunner()
                .withUserConfiguration(NormalizationWorkerAuditConfiguration.class)
                .withPropertyValues(
                        "spring.application.name=ircs-normalization-worker",
                        "spring.datasource.url=jdbc:h2:mem:normalization_worker_audit")
                .run(context -> assertThat(context).hasSingleBean(WorkerJobAuditWriter.class));
    }
}
