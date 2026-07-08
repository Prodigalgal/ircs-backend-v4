package com.prodigalgal.ircs.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class AggregationWorkerAuditConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AggregationWorkerAuditConfiguration.class)
            .withPropertyValues(
                    "spring.application.name=ircs-aggregation-worker",
                    "app.worker.audit.enabled=true",
                    "app.worker.audit.datasource-url=jdbc:postgresql://db.example/ircs",
                    "app.worker.audit.username=audit-user",
                    "app.worker.audit.password=audit-password");

    @Test
    void exposesWorkerJobAuditWriterWithServiceSource() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WorkerJobAuditWriter.class);

            WorkerJobAuditWriter writer = context.getBean(WorkerJobAuditWriter.class);
            assertThat(ReflectionTestUtils.getField(writer, "enabled")).isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(writer, "datasourceUrl"))
                    .isEqualTo("jdbc:postgresql://db.example/ircs");
            assertThat((String) ReflectionTestUtils.getField(writer, "jobSource"))
                    .startsWith("ircs-aggregation-worker@")
                    .contains("#");
        });
    }
}
