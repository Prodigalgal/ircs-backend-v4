package com.prodigalgal.ircs.opsalert.controller;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import com.prodigalgal.ircs.opsalert.application.AlertIngestionService;
import com.prodigalgal.ircs.opsalert.application.OpsAlertQueryService;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import com.prodigalgal.ircs.opsalert.dto.AlertEventFilter;
import com.prodigalgal.ircs.opsalert.dto.AlertEventIngestRequest;
import com.prodigalgal.ircs.opsalert.dto.AlertEventResponse;
import com.prodigalgal.ircs.opsalert.dto.AlertIngestionResponse;
import com.prodigalgal.ircs.opsalert.dto.HealingActionFilter;
import com.prodigalgal.ircs.opsalert.dto.HealingActionResponse;
import com.prodigalgal.ircs.opsalert.dto.IncidentFilter;
import com.prodigalgal.ircs.opsalert.dto.IncidentResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops-alert")
public class OpsAlertController {

    private final AlertIngestionService ingestionService;
    private final OpsAlertQueryService queryService;

    @PostMapping("/events")
    public ResponseEntity<AlertIngestionResponse> ingest(@Valid @RequestBody AlertEventIngestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionService.ingest(request));
    }

    @GetMapping("/events")
    public ResponseEntity<PageEnvelope<AlertEventResponse>> events(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceName,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(PageEnvelope.from(queryService.findEvents(
                pageable,
                new AlertEventFilter(severity, source, eventType, resourceType, resourceName, fingerprint, from, to))));
    }

    @GetMapping("/incidents")
    public ResponseEntity<PageEnvelope<IncidentResponse>> incidents(
            @PageableDefault(sort = "lastSeenAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceName,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(PageEnvelope.from(queryService.findIncidents(
                pageable,
                new IncidentFilter(status, severity, source, resourceType, resourceName, fingerprint, from, to))));
    }

    @GetMapping("/healing-actions")
    public ResponseEntity<PageEnvelope<HealingActionResponse>> healingActions(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) UUID incidentId,
            @RequestParam(required = false) HealingActionStatus status,
            @RequestParam(required = false) String policyKey,
            @RequestParam(required = false) String playbookKey,
            @RequestParam(required = false) Boolean dryRun,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(PageEnvelope.from(queryService.findHealingActions(
                pageable,
                new HealingActionFilter(incidentId, status, policyKey, playbookKey, dryRun, from, to))));
    }
}
