package com.prodigalgal.ircs.opsalert.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.HealingAction;
import com.prodigalgal.ircs.opsalert.domain.HealingPlan;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import com.prodigalgal.ircs.opsalert.dto.AlertEventIngestRequest;
import com.prodigalgal.ircs.opsalert.dto.AlertIngestionResponse;
import com.prodigalgal.ircs.opsalert.dto.OpsAlertMapper;
import com.prodigalgal.ircs.opsalert.infrastructure.OpsAlertRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AlertIngestionService {

    private final OpsAlertRepository repository;
    private final HealingPolicyEngine healingPolicyEngine;
    private final OpsAlertNotificationPublisher notificationPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AlertIngestionService(
            OpsAlertRepository repository,
            HealingPolicyEngine healingPolicyEngine,
            OpsAlertNotificationPublisher notificationPublisher,
            ObjectMapper objectMapper,
            @Qualifier("opsAlertClock") Clock clock) {
        this.repository = repository;
        this.healingPolicyEngine = healingPolicyEngine;
        this.notificationPublisher = notificationPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Transactional
    public AlertIngestionResponse ingest(AlertEventIngestRequest request) {
        Instant now = clock.instant();
        AlertEvent event = repository.insertEvent(toEvent(request, now));
        Incident incident = aggregateIncident(event, now);
        List<HealingAction> actions = healingPolicyEngine.plan(event, incident).stream()
                .map(plan -> repository.insertHealingAction(incident.id(), plan, now))
                .toList();
        notificationPublisher.publishIncidentNotification(event, incident, actions);
        return new AlertIngestionResponse(
                OpsAlertMapper.toResponse(event),
                OpsAlertMapper.toResponse(incident),
                actions.stream().map(OpsAlertMapper::toResponse).toList());
    }

    private Incident aggregateIncident(AlertEvent event, Instant now) {
        return repository.findOpenIncidentByFingerprint(event.fingerprint())
                .map(existing -> repository.updateIncidentForEvent(existing, event, now))
                .orElseGet(() -> insertOrUpdateAfterConcurrentInsert(event, now));
    }

    private Incident insertOrUpdateAfterConcurrentInsert(AlertEvent event, Instant now) {
        try {
            return repository.insertIncident(event, now);
        } catch (DuplicateKeyException ex) {
            return repository.findOpenIncidentByFingerprint(event.fingerprint())
                    .map(existing -> repository.updateIncidentForEvent(existing, event, now))
                    .orElseThrow(() -> ex);
        }
    }

    private AlertEvent toEvent(AlertEventIngestRequest request, Instant now) {
        return new AlertEvent(
                UUID.randomUUID(),
                now,
                request.observedAt() == null ? now : request.observedAt(),
                normalizeToken(request.source()),
                normalizeToken(request.eventType()),
                request.severity(),
                normalizeToken(request.resourceType()),
                request.resourceName().trim(),
                fingerprint(request),
                request.summary().trim(),
                detailsJson(request));
    }

    private String fingerprint(AlertEventIngestRequest request) {
        if (StringUtils.hasText(request.fingerprint())) {
            return request.fingerprint().trim().toLowerCase(Locale.ROOT);
        }
        String source = normalizeToken(request.source());
        String eventType = normalizeToken(request.eventType());
        String resourceType = normalizeToken(request.resourceType());
        String resourceName = request.resourceName().trim().toLowerCase(Locale.ROOT);
        String material = String.join("|", source, eventType, resourceType, resourceName);
        return "sha256:" + sha256(material);
    }

    private String detailsJson(AlertEventIngestRequest request) {
        if (request.details() == null || request.details().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.details());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Alert details must be JSON serializable", ex);
        }
    }

    private static String normalizeToken(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
