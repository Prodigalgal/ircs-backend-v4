package com.prodigalgal.ircs.ops.maintenance.application;


import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateCloseRequest;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateCreateRequest;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import com.prodigalgal.ircs.ops.maintenance.infrastructure.MaintenanceGateChangePublisher;
import com.prodigalgal.ircs.ops.maintenance.infrastructure.MaintenanceGateRepository;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.common.maintenance.MaintenanceGateCheckKind;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MaintenanceGateServiceTest {

    private final OpsConfigValues configValues = org.mockito.Mockito.mock(OpsConfigValues.class);
    private final MaintenanceGateChangePublisher changePublisher =
            org.mockito.Mockito.mock(MaintenanceGateChangePublisher.class);
    private MaintenanceGateService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:maintenance_gate_service_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table maintenance_operations (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint not null default 0,
                    operation_key varchar(128) not null,
                    owner_service varchar(128) not null,
                    resource_type varchar(128) not null,
                    resource_scope varchar(256) not null default '*',
                    mode varchar(32) not null,
                    status varchar(32) not null default 'ACTIVE',
                    reason varchar(512),
                    requested_by varchar(128),
                    correlation_id varchar(128),
                    expires_at timestamp not null,
                    closed_at timestamp,
                    close_reason varchar(512)
                )
                """);
        when(configValues.maintenanceGateDefaultTtl()).thenReturn(Duration.ofMinutes(15));
        when(configValues.maintenanceGateMaxTtl()).thenReturn(Duration.ofHours(6));
        service = new MaintenanceGateService(
                new MaintenanceGateRepository(new NamedParameterJdbcTemplate(dataSource)),
                jdbcTemplate,
                configValues,
                changePublisher);
    }

    @Test
    void createsGateWithDefaultScopeAndBlocksWriteChecks() {
        MaintenanceGateResponse created = service.create(new MaintenanceGateCreateRequest(
                "content-category-rebuild",
                "content-service",
                "category",
                "",
                MaintenanceGateMode.READ_ONLY,
                "rebuilding category tree",
                "admin",
                "corr",
                null,
                60L));

        assertThat(created.resourceScope()).isEqualTo("*");
        assertThat(service.check("content-service", "category", "42", MaintenanceGateCheckKind.WRITE).allowed())
                .isFalse();
        verify(changePublisher).publishCreated(created);
    }

    @Test
    void refusesTooLongTtl() {
        MaintenanceGateCreateRequest request = new MaintenanceGateCreateRequest(
                "too-long",
                "content-service",
                "video",
                "*",
                MaintenanceGateMode.READ_ONLY,
                "",
                "admin",
                "",
                null,
                Duration.ofHours(7).toSeconds());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl exceeds max ttl");
    }

    @Test
    void closeStopsBlocking() {
        MaintenanceGateResponse created = service.create(new MaintenanceGateCreateRequest(
                "credential-rotation",
                "credential-service",
                "provider",
                "MAIL",
                MaintenanceGateMode.QUIESCE_WRITES,
                "",
                "admin",
                "",
                null,
                60L));

        assertThat(service.check("credential-service", "provider", "MAIL", MaintenanceGateCheckKind.WRITE).allowed())
                .isFalse();

        MaintenanceGateResponse closed = service.close(created.id(), new MaintenanceGateCloseRequest("done"));

        assertThat(service.check("credential-service", "provider", "MAIL", MaintenanceGateCheckKind.WRITE).allowed())
                .isTrue();
        verify(changePublisher).publishClosed(closed);
    }
}
