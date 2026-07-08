package com.prodigalgal.ircs.metadata.config;

import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MetadataWorkerAuditConfiguration {

    @Bean
    WorkerJobAuditWriter workerJobAuditWriter(
            @Value("${app.worker.audit.enabled:true}") boolean enabled,
            @Value("${app.worker.audit.datasource-url:${SPRING_DATASOURCE_URL:${DB_URL:${spring.datasource.url:}}}}") String datasourceUrl,
            @Value("${app.worker.audit.username:${SPRING_DATASOURCE_USERNAME:${DB_USERNAME:${spring.datasource.username:}}}}") String username,
            @Value("${app.worker.audit.password:${SPRING_DATASOURCE_PASSWORD:${DB_PASSWORD:${spring.datasource.password:}}}}") String password,
            @Value("${app.worker.audit.source:${spring.application.name:ircs-metadata-worker}}") String jobSource) {
        return new WorkerJobAuditWriter(enabled, datasourceUrl, username, password, jobSource);
    }
}
