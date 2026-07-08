package com.prodigalgal.ircs.opsalert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import com.prodigalgal.ircs.opsalert.dto.AlertEventFilter;
import com.prodigalgal.ircs.opsalert.dto.AlertEventIngestRequest;
import com.prodigalgal.ircs.opsalert.dto.HealingActionFilter;
import com.prodigalgal.ircs.opsalert.dto.IncidentFilter;
import com.prodigalgal.ircs.opsalert.infrastructure.OpsAlertRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AlertIngestionServiceTest {

    @Test
    void repeatedEventsAreAggregatedIntoSingleIncidentAndDryRunAction() {
        Fixture fixture = fixture("aggregate");
        AlertEventIngestRequest first = request("worker-runtime-stalled");
        AlertEventIngestRequest second = request("worker-runtime-stalled");

        var firstResponse = fixture.service().ingest(first);
        var secondResponse = fixture.service().ingest(second);

        assertThat(secondResponse.incident().id()).isEqualTo(firstResponse.incident().id());
        assertThat(secondResponse.incident().occurrenceCount()).isEqualTo(2);
        assertThat(secondResponse.incident().severity()).isEqualTo(AlertSeverity.ERROR);
        assertThat(secondResponse.healingActions()).hasSize(1);
        assertThat(secondResponse.healingActions().getFirst().status()).isEqualTo(HealingActionStatus.DRY_RUN);
        assertThat(secondResponse.healingActions().getFirst().playbookKey()).isEqualTo("runtime-queue.recover-stale");

        var incidents = fixture.repository().findIncidents(
                PageRequest.of(0, 10), new IncidentFilter(IncidentStatus.OPEN, null, null, null, null, null, null, null));
        var events = fixture.repository().findEvents(PageRequest.of(0, 10), new AlertEventFilter(
                null, null, null, null, null, null, null, null));
        var actions = fixture.repository().findHealingActions(PageRequest.of(0, 10), new HealingActionFilter(
                firstResponse.incident().id(), HealingActionStatus.DRY_RUN, null, null, true, null, null));

        assertThat(incidents.getTotalElements()).isEqualTo(1);
        assertThat(events.getTotalElements()).isEqualTo(2);
        assertThat(actions.getTotalElements()).isEqualTo(2);
        verify(fixture.notificationPublisher(), times(2)).publishIncidentNotification(any(), any(), anyList());
    }

    private record Fixture(
            AlertIngestionService service,
            OpsAlertRepository repository,
            OpsAlertNotificationPublisher notificationPublisher) {
    }

    private static Fixture fixture(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:mem:ops_alert_" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createTables(jdbcTemplate);

        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.booleanValue(HealingPolicyEngine.ENABLED_KEY, true)).thenReturn(true);
        when(runtimeConfig.booleanValue(HealingPolicyEngine.DRY_RUN_KEY, true)).thenReturn(true);
        when(runtimeConfig.stringValue(HealingPolicyEngine.MIN_SEVERITY_KEY, AlertSeverity.WARNING.name()))
                .thenReturn(AlertSeverity.WARNING.name());

        ObjectMapper objectMapper = new ObjectMapper();
        OpsAlertRepository repository = new OpsAlertRepository(new NamedParameterJdbcTemplate(dataSource));
        HealingPolicyEngine policyEngine = new HealingPolicyEngine(runtimeConfig, objectMapper);
        OpsAlertNotificationPublisher notificationPublisher = mock(OpsAlertNotificationPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-22T10:15:30Z"), ZoneOffset.UTC);
        return new Fixture(
                new AlertIngestionService(repository, policyEngine, notificationPublisher, objectMapper, clock),
                repository,
                notificationPublisher);
    }

    private static AlertEventIngestRequest request(String fingerprint) {
        return new AlertEventIngestRequest(
                "ops-service",
                "RUNTIME_QUEUE_STALE_INFLIGHT",
                AlertSeverity.ERROR,
                "queue",
                "metadata-runtime",
                fingerprint,
                "metadata-runtime stalled",
                Instant.parse("2026-06-22T10:15:00Z"),
                Map.of("queue", "metadata-runtime", "stalledSeconds", 900));
    }

    private static void createTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table ops_alert_events (
                    id uuid primary key,
                    created_at timestamp not null,
                    observed_at timestamp not null,
                    source varchar(128) not null,
                    event_type varchar(128) not null,
                    severity varchar(32) not null,
                    resource_type varchar(128) not null,
                    resource_name varchar(256) not null,
                    fingerprint varchar(256) not null,
                    summary varchar(512) not null,
                    details_json text
                )
                """);
        jdbcTemplate.execute("""
                create table ops_incidents (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint not null,
                    fingerprint varchar(256) not null,
                    status varchar(32) not null,
                    severity varchar(32) not null,
                    title varchar(512) not null,
                    source varchar(128) not null,
                    resource_type varchar(128) not null,
                    resource_name varchar(256) not null,
                    first_seen_at timestamp not null,
                    last_seen_at timestamp not null,
                    recovered_at timestamp,
                    occurrence_count bigint not null,
                    last_reason text,
                    last_event_id uuid
                )
                """);
        jdbcTemplate.execute("""
                create table ops_healing_actions (
                    id uuid primary key,
                    incident_id uuid not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    policy_key varchar(128) not null,
                    playbook_key varchar(128) not null,
                    dry_run boolean not null,
                    status varchar(32) not null,
                    request_payload text,
                    result_payload text,
                    started_at timestamp,
                    finished_at timestamp
                )
                """);
    }
}
