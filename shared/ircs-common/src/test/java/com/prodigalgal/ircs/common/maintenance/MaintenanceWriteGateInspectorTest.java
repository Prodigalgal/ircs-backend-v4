package com.prodigalgal.ircs.common.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MaintenanceWriteGateInspectorTest {

    private static final Instant NOW = Instant.parse("2026-06-11T10:15:30Z");

    @Test
    void activeExactScopeWriteGateBlocksWriteAndWinsOverWildcard() {
        Fixture fixture = fixture("exact_scope");
        UUID wildcard = UUID.randomUUID();
        UUID exact = UUID.randomUUID();
        fixture.insert(wildcard, "wildcard-op", "content-service", "video", "*", "READ_ONLY", "wildcard", NOW.plusSeconds(600), "ACTIVE");
        fixture.insert(exact, "exact-op", "content-service", "video", "video-1", "QUIESCE_WRITES", "exact", NOW.plusSeconds(600), "ACTIVE");

        MaintenanceGateDecision decision = fixture.inspector().checkWrite("content-service", "video", "video-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.operationId()).isEqualTo(exact);
        assertThat(decision.operationKey()).isEqualTo("exact-op");
        assertThat(decision.reason()).isEqualTo("exact");
    }

    @Test
    void expiredAndClosedGatesDoNotBlock() {
        Fixture fixture = fixture("ignored");
        fixture.insert(UUID.randomUUID(), "expired", "content-service", "video", "*", "READ_ONLY", "", NOW.minusSeconds(1), "ACTIVE");
        fixture.insert(UUID.randomUUID(), "closed", "content-service", "video", "*", "READ_ONLY", "", NOW.plusSeconds(600), "CLOSED");

        MaintenanceGateDecision decision = fixture.inspector().checkWrite("content-service", "video", "video-1");

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void pauseConsumersBlocksConsumersButNotHttpWrites() {
        Fixture fixture = fixture("consumer");
        UUID id = UUID.randomUUID();
        fixture.insert(id, "pause-consumers", "storage-service", "media", "*", "PAUSE_CONSUMERS", "", NOW.plusSeconds(600), "ACTIVE");

        assertThat(fixture.inspector().checkWrite("storage-service", "media", "cover").allowed()).isTrue();

        MaintenanceGateDecision consumer = fixture.inspector().checkConsumer("storage-service", "media", "cover");
        assertThat(consumer.allowed()).isFalse();
        assertThat(consumer.operationId()).isEqualTo(id);
    }

    @Test
    void assertWriteAllowedThrowsLockedExceptionWithDecision() {
        Fixture fixture = fixture("throwing");
        UUID id = UUID.randomUUID();
        fixture.insert(id, "readonly", "credential-service", "provider", "MAIL", "READ_ONLY", "rotating", NOW.plusSeconds(600), "ACTIVE");

        assertThatThrownBy(() -> fixture.inspector().assertWriteAllowed("credential-service", "provider", "MAIL"))
                .isInstanceOf(MaintenanceGateLockedException.class)
                .hasMessageContaining("maintenance gate blocks write")
                .satisfies(ex -> assertThat(((MaintenanceGateLockedException) ex).decision().operationId()).isEqualTo(id));
    }

    private static Fixture fixture(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:maintenance_gate_" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
        MaintenanceWriteGateInspector inspector = new MaintenanceWriteGateInspector(
                new NamedParameterJdbcTemplate(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(jdbcTemplate, inspector);
    }

    private record Fixture(JdbcTemplate jdbcTemplate, MaintenanceWriteGateInspector inspector) {
        void insert(
                UUID id,
                String operationKey,
                String ownerService,
                String resourceType,
                String resourceScope,
                String mode,
                String reason,
                Instant expiresAt,
                String status) {
            jdbcTemplate.update("""
                    insert into maintenance_operations (
                        id, created_at, updated_at, operation_key, owner_service, resource_type,
                        resource_scope, mode, status, reason, expires_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW.minusSeconds(60)),
                    operationKey,
                    ownerService,
                    resourceType,
                    resourceScope,
                    mode,
                    status,
                    reason,
                    Timestamp.from(expiresAt));
        }
    }
}
