package com.prodigalgal.ircs.common.maintenance;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;

public final class MaintenanceWriteGateInspector {

    private static final String ACTIVE_GATE_SQL = """
            select id, operation_key, owner_service, resource_type, resource_scope, mode, reason, expires_at
              from maintenance_operations
             where status = 'ACTIVE'
               and expires_at > :now
               and lower(owner_service) = lower(:ownerService)
               and lower(resource_type) = lower(:resourceType)
               and mode in (:blockingModes)
               and (resource_scope = '*' or resource_scope = :resourceScope)
             order by case when resource_scope = :resourceScope then 0 else 1 end, created_at desc
             limit 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    public MaintenanceWriteGateInspector(JdbcTemplate jdbcTemplate) {
        this(new NamedParameterJdbcTemplate(jdbcTemplate), Clock.systemUTC());
    }

    public MaintenanceWriteGateInspector(NamedParameterJdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public MaintenanceGateDecision checkWrite(String ownerService, String resourceType, String resourceScope) {
        return check(ownerService, resourceType, resourceScope, MaintenanceGateCheckKind.WRITE);
    }

    public MaintenanceGateDecision checkConsumer(String ownerService, String resourceType, String resourceScope) {
        return check(ownerService, resourceType, resourceScope, MaintenanceGateCheckKind.CONSUMER);
    }

    public MaintenanceGateDecision check(
            String ownerService,
            String resourceType,
            String resourceScope,
            MaintenanceGateCheckKind checkKind) {
        String owner = required(ownerService, "ownerService");
        String type = required(resourceType, "resourceType");
        String scope = normalizeScope(resourceScope);
        MaintenanceGateCheckKind kind = checkKind == null ? MaintenanceGateCheckKind.WRITE : checkKind;
        List<String> blockingModes = blockingModes(kind);
        if (blockingModes.isEmpty()) {
            return MaintenanceGateDecision.allowed(kind, owner, type, scope);
        }
        Map<String, Object> params = Map.of(
                "now", Timestamp.from(clock.instant()),
                "ownerService", owner,
                "resourceType", type,
                "resourceScope", scope,
                "blockingModes", blockingModes);
        List<MaintenanceGateDecision> decisions = jdbcTemplate.query(ACTIVE_GATE_SQL, params, (rs, rowNum) ->
                MaintenanceGateDecision.blocked(
                        kind,
                        UUID.fromString(rs.getString("id")),
                        rs.getString("operation_key"),
                        rs.getString("owner_service"),
                        rs.getString("resource_type"),
                        rs.getString("resource_scope"),
                        MaintenanceGateMode.parse(rs.getString("mode")),
                        rs.getString("reason"),
                        rs.getTimestamp("expires_at").toInstant()));
        return decisions.isEmpty()
                ? MaintenanceGateDecision.allowed(kind, owner, type, scope)
                : decisions.getFirst();
    }

    public void assertWriteAllowed(String ownerService, String resourceType, String resourceScope) {
        MaintenanceGateDecision decision = checkWrite(ownerService, resourceType, resourceScope);
        if (!decision.allowed()) {
            throw new MaintenanceGateLockedException(decision);
        }
    }

    public void assertConsumerAllowed(String ownerService, String resourceType, String resourceScope) {
        MaintenanceGateDecision decision = checkConsumer(ownerService, resourceType, resourceScope);
        if (!decision.allowed()) {
            throw new MaintenanceGateLockedException(decision);
        }
    }

    private static List<String> blockingModes(MaintenanceGateCheckKind checkKind) {
        return Arrays.stream(MaintenanceGateMode.values())
                .filter(mode -> checkKind == MaintenanceGateCheckKind.WRITE
                        ? mode.blocksWrites()
                        : mode.blocksConsumers())
                .map(Enum::name)
                .toList();
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeScope(String value) {
        return StringUtils.hasText(value) ? value.trim() : "*";
    }
}
