package com.prodigalgal.ircs.apigateway;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ApiGatewayDataSourceWarmup {

    private static final Logger log = LoggerFactory.getLogger(ApiGatewayDataSourceWarmup.class);

    private final DataSource dataSource;

    @Value("${app.gateway.datasource-warmup.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    void warmup() {
        if (!enabled) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                log.warn("api.gateway.datasource.warmup.invalid");
            }
        } catch (SQLException ex) {
            log.warn("api.gateway.datasource.warmup.failed", ex);
        }
    }
}
