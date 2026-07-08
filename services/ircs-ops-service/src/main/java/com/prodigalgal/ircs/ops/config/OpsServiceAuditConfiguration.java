package com.prodigalgal.ircs.ops.config;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import com.prodigalgal.ircs.common.audit.WorkerJobAuditWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class OpsServiceAuditConfiguration {

    @Bean
    FilterRegistrationBean<ServiceRequestAuditFilter> serviceRequestAuditFilter(
            JdbcTemplate jdbcTemplate,
            @Value("${app.service.audit.enabled:true}") boolean enabled,
            @Value("${app.service.audit.source:${spring.application.name:ircs-ops-service}}") String requestSource) {
        FilterRegistrationBean<ServiceRequestAuditFilter> registration = new FilterRegistrationBean<>(
                new ServiceRequestAuditFilter(enabled, requestSource, jdbcTemplate));
        registration.setName("serviceRequestAuditFilter");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 20);
        return registration;
    }

    @Bean
    WorkerJobAuditWriter workerJobAuditWriter(
            @Value("${app.worker.audit.enabled:true}") boolean enabled,
            @Value("${app.worker.audit.datasource-url:${SPRING_DATASOURCE_URL:${DB_URL:${spring.datasource.url:}}}}") String datasourceUrl,
            @Value("${app.worker.audit.username:${SPRING_DATASOURCE_USERNAME:${DB_USERNAME:${spring.datasource.username:}}}}") String username,
            @Value("${app.worker.audit.password:${SPRING_DATASOURCE_PASSWORD:${DB_PASSWORD:${spring.datasource.password:}}}}") String password,
            @Value("${app.worker.audit.source:${spring.application.name:ircs-ops-service}}") String jobSource) {
        return new WorkerJobAuditWriter(enabled, datasourceUrl, username, password, jobSource);
    }
}
