package com.prodigalgal.ircs.workerruntime;

import com.prodigalgal.ircs.common.audit.ServiceRequestAuditFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class IrcsWorkerRuntimeAuditConfiguration {

    @Bean
    FilterRegistrationBean<ServiceRequestAuditFilter> serviceRequestAuditFilter(
            JdbcTemplate jdbcTemplate,
            @Value("${app.runtime.audit.enabled:${app.service.audit.enabled:true}}") boolean enabled,
            @Value("${app.runtime.audit.source:${spring.application.name:ircs-worker-runtime}}") String requestSource) {
        FilterRegistrationBean<ServiceRequestAuditFilter> registration = new FilterRegistrationBean<>(
                new ServiceRequestAuditFilter(enabled, requestSource, jdbcTemplate));
        registration.setName("serviceRequestAuditFilter");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 20);
        return registration;
    }
}
