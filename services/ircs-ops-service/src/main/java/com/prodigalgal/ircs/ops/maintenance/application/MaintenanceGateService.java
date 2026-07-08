package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateCheckKind;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateDecision;
import com.prodigalgal.ircs.common.maintenance.MaintenanceWriteGateInspector;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceGateDraft;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateCloseRequest;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateCreateRequest;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import com.prodigalgal.ircs.ops.maintenance.infrastructure.MaintenanceGateChangePublisher;
import com.prodigalgal.ircs.ops.maintenance.infrastructure.MaintenanceGateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MaintenanceGateService {

    private final MaintenanceGateRepository repository;
    private final MaintenanceWriteGateInspector inspector;
    private final OpsConfigValues configValues;
    private final MaintenanceGateChangePublisher changePublisher;

    MaintenanceGateService(
            MaintenanceGateRepository repository,
            JdbcTemplate jdbcTemplate,
            OpsConfigValues configValues,
            MaintenanceGateChangePublisher changePublisher) {
        this.repository = repository;
        this.inspector = new MaintenanceWriteGateInspector(jdbcTemplate);
        this.configValues = configValues;
        this.changePublisher = changePublisher;
    }

    public MaintenanceGateResponse create(MaintenanceGateCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Instant now = Instant.now();
        UUID id = IrcsUuidGenerators.nextId();
        Instant expiresAt = expiresAt(request, now);
        Duration ttl = Duration.between(now, expiresAt);
        if (ttl.compareTo(configValues.maintenanceGateMaxTtl()) > 0) {
            throw new IllegalArgumentException("maintenance gate ttl exceeds max ttl");
        }
        MaintenanceGateDraft draft = new MaintenanceGateDraft(
                id,
                now,
                required(request.operationKey(), "operationKey"),
                required(request.ownerService(), "ownerService"),
                required(request.resourceType(), "resourceType"),
                normalize(request.resourceScope(), "*"),
                mode(request.mode()),
                normalize(request.reason(), ""),
                normalize(request.requestedBy(), "ops"),
                normalize(request.correlationId(), ""),
                expiresAt);
        MaintenanceGateResponse response = repository.create(draft);
        changePublisher.publishCreated(response);
        return response;
    }

    public List<MaintenanceGateResponse> active() {
        return repository.findActive(Instant.now());
    }

    public MaintenanceGateResponse close(UUID id, MaintenanceGateCloseRequest request) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        MaintenanceGateResponse response = repository.close(id, request == null ? "" : request.reason(), Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("maintenance gate not found: " + id));
        changePublisher.publishClosed(response);
        return response;
    }

    public MaintenanceGateDecision check(
            String ownerService,
            String resourceType,
            String resourceScope,
            MaintenanceGateCheckKind checkKind) {
        return inspector.check(ownerService, resourceType, resourceScope, checkKind);
    }

    private Instant expiresAt(MaintenanceGateCreateRequest request, Instant now) {
        if (request.expiresAt() != null) {
            if (!request.expiresAt().isAfter(now)) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            return request.expiresAt();
        }
        Duration ttl = request.ttlSeconds() == null
                ? configValues.maintenanceGateDefaultTtl()
                : Duration.ofSeconds(request.ttlSeconds());
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        return now.plus(ttl);
    }

    private static MaintenanceGateMode mode(MaintenanceGateMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return mode;
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
