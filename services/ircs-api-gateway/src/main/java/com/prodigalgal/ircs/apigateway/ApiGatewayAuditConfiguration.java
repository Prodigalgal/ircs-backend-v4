package com.prodigalgal.ircs.apigateway;

import com.prodigalgal.ircs.common.audit.ProxyRequestAuditWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class ApiGatewayAuditConfiguration {

    @Bean
    ProxyRequestAuditWriter proxyRequestAuditWriter(
            JdbcTemplate jdbcTemplate,
            @Value("${app.gateway.audit.enabled:true}") boolean enabled,
            @Value("${spring.application.name:ircs-api-gateway}") String requestSource) {
        return new ProxyRequestAuditWriter(enabled, jdbcTemplate::update, requestSource);
    }
}
