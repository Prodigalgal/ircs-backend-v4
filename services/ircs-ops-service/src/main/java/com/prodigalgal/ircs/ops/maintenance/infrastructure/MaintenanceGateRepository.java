package com.prodigalgal.ircs.ops.maintenance.infrastructure;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceGateDraft;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MaintenanceGateRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public MaintenanceGateResponse create(MaintenanceGateDraft draft) {
        jdbcTemplate.update("""
                insert into maintenance_operations (
                    id, created_at, updated_at, version, operation_key, owner_service, resource_type,
                    resource_scope, mode, status, reason, requested_by, correlation_id, expires_at
                ) values (
                    :id, :now, :now, 0, :operationKey, :ownerService, :resourceType,
                    :resourceScope, :mode, 'ACTIVE', :reason, :requestedBy, :correlationId, :expiresAt
                )
                """, Map.ofEntries(
                Map.entry("id", draft.id()),
                Map.entry("now", Timestamp.from(draft.now())),
                Map.entry("operationKey", draft.operationKey()),
                Map.entry("ownerService", draft.ownerService()),
                Map.entry("resourceType", draft.resourceType()),
                Map.entry("resourceScope", draft.resourceScope()),
                Map.entry("mode", draft.mode().name()),
                Map.entry("reason", draft.reason()),
                Map.entry("requestedBy", draft.requestedBy()),
                Map.entry("correlationId", draft.correlationId()),
                Map.entry("expiresAt", Timestamp.from(draft.expiresAt()))));
        return findById(draft.id()).orElseThrow();
    }

    public List<MaintenanceGateResponse> findActive(Instant now) {
        return jdbcTemplate.query("""
                select *
                  from maintenance_operations
                 where status = 'ACTIVE'
                   and expires_at > :now
                 order by created_at desc
                """, Map.of("now", Timestamp.from(now)), this::mapRow);
    }

    public Optional<MaintenanceGateResponse> findById(UUID id) {
        List<MaintenanceGateResponse> rows = jdbcTemplate.query("""
                select *
                  from maintenance_operations
                 where id = :id
                """, Map.of("id", id), this::mapRow);
        return rows.stream().findFirst();
    }

    public Optional<MaintenanceGateResponse> close(UUID id, String reason, Instant now) {
        jdbcTemplate.update("""
                update maintenance_operations
                   set status = 'CLOSED',
                       updated_at = :now,
                       closed_at = :now,
                       close_reason = :reason,
                       version = version + 1
                 where id = :id
                   and status = 'ACTIVE'
                """, Map.of(
                "id", id,
                "now", Timestamp.from(now),
                "reason", reason == null ? "" : reason));
        return findById(id);
    }

    private MaintenanceGateResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MaintenanceGateResponse(
                UUID.fromString(rs.getString("id")),
                timestamp(rs, "created_at"),
                timestamp(rs, "updated_at"),
                rs.getLong("version"),
                rs.getString("operation_key"),
                rs.getString("owner_service"),
                rs.getString("resource_type"),
                rs.getString("resource_scope"),
                MaintenanceGateMode.parse(rs.getString("mode")),
                MaintenanceGateStatus.parse(rs.getString("status")),
                rs.getString("reason"),
                rs.getString("requested_by"),
                rs.getString("correlation_id"),
                timestamp(rs, "expires_at"),
                timestamp(rs, "closed_at"),
                rs.getString("close_reason"));
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
