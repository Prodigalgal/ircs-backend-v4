package com.prodigalgal.ircs.ops.maintenance.infrastructure;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceGateDraft;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MaintenanceGateRepositoryTest {

    @Test
    void createsListsAndClosesActiveMaintenanceGate() {
        Fixture fixture = fixture("repo");
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-11T10:15:30Z");

        MaintenanceGateResponse created = fixture.repository().create(new MaintenanceGateDraft(
                id,
                now,
                "media-path-migration",
                "storage-service",
                "media",
                "*",
                MaintenanceGateMode.QUIESCE_WRITES,
                "moving paths",
                "admin",
                "corr-1",
                now.plusSeconds(900)));

        assertThat(created.id()).isEqualTo(id);
        assertThat(created.version()).isZero();
        assertThat(created.status()).isEqualTo(MaintenanceGateStatus.ACTIVE);
        assertThat(fixture.repository().findActive(now).stream().map(MaintenanceGateResponse::id))
                .containsExactly(id);

        MaintenanceGateResponse closed = fixture.repository().close(id, "done", now.plusSeconds(60)).orElseThrow();

        assertThat(closed.status()).isEqualTo(MaintenanceGateStatus.CLOSED);
        assertThat(closed.version()).isEqualTo(1);
        assertThat(closed.closeReason()).isEqualTo("done");
        assertThat(fixture.repository().findActive(now.plusSeconds(61))).isEmpty();
    }

    private static Fixture fixture(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:maintenance_gate_ops_" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
        return new Fixture(new MaintenanceGateRepository(new NamedParameterJdbcTemplate(dataSource)));
    }

    private record Fixture(MaintenanceGateRepository repository) {
    }
}
