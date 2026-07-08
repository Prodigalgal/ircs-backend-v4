package com.prodigalgal.ircs.opsalert.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.HealingAction;
import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import com.prodigalgal.ircs.opsalert.dto.AlertEventFilter;
import com.prodigalgal.ircs.opsalert.dto.HealingActionFilter;
import com.prodigalgal.ircs.opsalert.dto.IncidentFilter;
import com.prodigalgal.ircs.opsalert.infrastructure.OpsAlertRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class OpsAlertQueryServiceTest {

    private final OpsAlertRepository repository = org.mockito.Mockito.mock(OpsAlertRepository.class);
    private final OpsAlertQueryService service = new OpsAlertQueryService(repository);

    @Test
    void cachesUnfilteredEventFirstPage() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        AlertEventFilter filter = new AlertEventFilter(null, null, null, null, null, null, null, null);
        Page<AlertEvent> events = new PageImpl<>(List.of(event("pod restart")), pageable, 1);
        when(repository.findEvents(pageable, filter)).thenReturn(events);

        service.findEvents(pageable, filter);
        service.findEvents(pageable, filter);

        verify(repository, times(1)).findEvents(pageable, filter);
    }

    @Test
    void filteredEventQueryBypassesFirstPageCache() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        AlertEventFilter filter = new AlertEventFilter(AlertSeverity.WARNING, null, null, null, null, null, null, null);
        Page<AlertEvent> events = new PageImpl<>(List.of(event("warning")), pageable, 1);
        when(repository.findEvents(pageable, filter)).thenReturn(events);

        assertEquals(1, service.findEvents(pageable, filter).getTotalElements());
        assertEquals(1, service.findEvents(pageable, filter).getTotalElements());

        verify(repository, times(2)).findEvents(pageable, filter);
    }

    @Test
    void warmFirstPagesPrimesAllDefaultFirstPageCaches() {
        Pageable eventsPage = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Pageable incidentsPage = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "lastSeenAt"));
        Pageable healingActionsPage = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        AlertEventFilter eventFilter = new AlertEventFilter(null, null, null, null, null, null, null, null);
        IncidentFilter incidentFilter = new IncidentFilter(null, null, null, null, null, null, null, null);
        HealingActionFilter healingActionFilter = new HealingActionFilter(null, null, null, null, null, null, null);
        when(repository.findEvents(eventsPage, eventFilter))
                .thenReturn(new PageImpl<>(List.of(event("event")), eventsPage, 1));
        when(repository.findIncidents(incidentsPage, incidentFilter))
                .thenReturn(new PageImpl<>(List.of(incident()), incidentsPage, 1));
        when(repository.findHealingActions(healingActionsPage, healingActionFilter))
                .thenReturn(new PageImpl<>(List.of(healingAction()), healingActionsPage, 1));

        assertEquals(3, service.warmFirstPages(20));
        assertEquals(1, service.findEvents(eventsPage, eventFilter).getTotalElements());

        verify(repository, times(1)).findEvents(eventsPage, eventFilter);
        verify(repository, times(1)).findIncidents(incidentsPage, incidentFilter);
        verify(repository, times(1)).findHealingActions(healingActionsPage, healingActionFilter);
    }

    private AlertEvent event(String summary) {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new AlertEvent(
                UUID.randomUUID(),
                now,
                now,
                "ops",
                "service.health",
                AlertSeverity.INFO,
                "pod",
                "ircs-demo",
                "fingerprint",
                summary,
                "{}");
    }

    private Incident incident() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new Incident(
                UUID.randomUUID(),
                now,
                now,
                1,
                "fingerprint",
                IncidentStatus.OPEN,
                AlertSeverity.INFO,
                "incident",
                "ops",
                "pod",
                "ircs-demo",
                now,
                now,
                null,
                1,
                "reason",
                UUID.randomUUID());
    }

    private HealingAction healingAction() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new HealingAction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                now,
                now,
                "policy",
                "playbook",
                true,
                HealingActionStatus.EXECUTED,
                "{}",
                "{}",
                now,
                now);
    }
}
