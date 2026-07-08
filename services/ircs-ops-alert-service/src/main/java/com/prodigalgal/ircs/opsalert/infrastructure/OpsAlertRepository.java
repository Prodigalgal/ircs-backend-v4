package com.prodigalgal.ircs.opsalert.infrastructure;

import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.HealingAction;
import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import com.prodigalgal.ircs.opsalert.domain.HealingPlan;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import com.prodigalgal.ircs.opsalert.dto.AlertEventFilter;
import com.prodigalgal.ircs.opsalert.dto.HealingActionFilter;
import com.prodigalgal.ircs.opsalert.dto.IncidentFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class OpsAlertRepository {

    private static final Map<String, String> EVENT_SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("createdAt", "created_at"),
            Map.entry("observedAt", "observed_at"),
            Map.entry("source", "source"),
            Map.entry("eventType", "event_type"),
            Map.entry("severity", "severity"),
            Map.entry("resourceType", "resource_type"),
            Map.entry("resourceName", "resource_name"),
            Map.entry("fingerprint", "fingerprint"));
    private static final Map<String, String> INCIDENT_SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("createdAt", "created_at"),
            Map.entry("updatedAt", "updated_at"),
            Map.entry("lastSeenAt", "last_seen_at"),
            Map.entry("firstSeenAt", "first_seen_at"),
            Map.entry("status", "status"),
            Map.entry("severity", "severity"),
            Map.entry("source", "source"),
            Map.entry("resourceType", "resource_type"),
            Map.entry("resourceName", "resource_name"),
            Map.entry("occurrenceCount", "occurrence_count"));
    private static final Map<String, String> ACTION_SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("createdAt", "created_at"),
            Map.entry("updatedAt", "updated_at"),
            Map.entry("policyKey", "policy_key"),
            Map.entry("playbookKey", "playbook_key"),
            Map.entry("status", "status"),
            Map.entry("dryRun", "dry_run"));
    private static final String DEFAULT_EVENT_ORDER = " order by created_at desc, id desc";
    private static final String DEFAULT_INCIDENT_ORDER = " order by last_seen_at desc, id desc";
    private static final String DEFAULT_ACTION_ORDER = " order by created_at desc, id desc";
    private static final RowMapper<AlertEvent> EVENT_MAPPER = OpsAlertRepository::mapEvent;
    private static final RowMapper<Incident> INCIDENT_MAPPER = OpsAlertRepository::mapIncident;
    private static final RowMapper<HealingAction> ACTION_MAPPER = OpsAlertRepository::mapAction;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AlertEvent insertEvent(AlertEvent event) {
        jdbcTemplate.update("""
                insert into ops_alert_events (
                    id, created_at, observed_at, source, event_type, severity, resource_type, resource_name,
                    fingerprint, summary, details_json
                ) values (
                    :id, :createdAt, :observedAt, :source, :eventType, :severity, :resourceType, :resourceName,
                    :fingerprint, :summary, :detailsJson
                )
                """, eventParams(event));
        return event;
    }

    public Optional<Incident> findOpenIncidentByFingerprint(String fingerprint) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fingerprint", fingerprint)
                .addValue("recoveredStatus", IncidentStatus.RECOVERED.name());
        List<Incident> incidents = jdbcTemplate.query("""
                select id, created_at, updated_at, version, fingerprint, status, severity, title, source,
                       resource_type, resource_name, first_seen_at, last_seen_at, recovered_at,
                       occurrence_count, last_reason, last_event_id
                  from ops_incidents
                 where fingerprint = :fingerprint and status <> :recoveredStatus
                 order by last_seen_at desc, id desc
                 limit 1
                """, params, INCIDENT_MAPPER);
        return incidents.stream().findFirst();
    }

    public Incident insertIncident(AlertEvent event, Instant now) {
        Incident incident = new Incident(
                UUID.randomUUID(),
                now,
                now,
                0L,
                event.fingerprint(),
                IncidentStatus.OPEN,
                event.severity(),
                event.summary(),
                event.source(),
                event.resourceType(),
                event.resourceName(),
                event.observedAt(),
                event.observedAt(),
                null,
                1L,
                event.summary(),
                event.id());
        jdbcTemplate.update("""
                insert into ops_incidents (
                    id, created_at, updated_at, version, fingerprint, status, severity, title, source,
                    resource_type, resource_name, first_seen_at, last_seen_at, recovered_at,
                    occurrence_count, last_reason, last_event_id
                ) values (
                    :id, :createdAt, :updatedAt, :version, :fingerprint, :status, :severity, :title, :source,
                    :resourceType, :resourceName, :firstSeenAt, :lastSeenAt, :recoveredAt,
                    :occurrenceCount, :lastReason, :lastEventId
                )
                """, incidentParams(incident));
        return incident;
    }

    public Incident updateIncidentForEvent(Incident existing, AlertEvent event, Instant now) {
        AlertSeverity severity = AlertSeverity.max(existing.severity(), event.severity());
        Incident updated = new Incident(
                existing.id(),
                existing.createdAt(),
                now,
                existing.version() + 1,
                existing.fingerprint(),
                existing.status(),
                severity,
                existing.title(),
                existing.source(),
                existing.resourceType(),
                existing.resourceName(),
                existing.firstSeenAt(),
                event.observedAt().isAfter(existing.lastSeenAt()) ? event.observedAt() : existing.lastSeenAt(),
                existing.recoveredAt(),
                existing.occurrenceCount() + 1,
                event.summary(),
                event.id());
        jdbcTemplate.update("""
                update ops_incidents
                   set updated_at = :updatedAt,
                       version = :version,
                       severity = :severity,
                       last_seen_at = :lastSeenAt,
                       occurrence_count = :occurrenceCount,
                       last_reason = :lastReason,
                       last_event_id = :lastEventId
                 where id = :id
                """, incidentParams(updated));
        return updated;
    }

    public HealingAction insertHealingAction(UUID incidentId, HealingPlan plan, Instant now) {
        HealingAction action = new HealingAction(
                UUID.randomUUID(),
                incidentId,
                now,
                now,
                plan.policyKey(),
                plan.playbookKey(),
                plan.dryRun(),
                plan.status(),
                plan.requestPayload(),
                plan.resultPayload(),
                now,
                now);
        jdbcTemplate.update("""
                insert into ops_healing_actions (
                    id, incident_id, created_at, updated_at, policy_key, playbook_key, dry_run, status,
                    request_payload, result_payload, started_at, finished_at
                ) values (
                    :id, :incidentId, :createdAt, :updatedAt, :policyKey, :playbookKey, :dryRun, :status,
                    :requestPayload, :resultPayload, :startedAt, :finishedAt
                )
                """, actionParams(action));
        return action;
    }

    public Page<AlertEvent> findEvents(Pageable pageable, AlertEventFilter filter) {
        QueryParts parts = eventWhere(filter);
        String sql = """
                select id, created_at, observed_at, source, event_type, severity, resource_type,
                       resource_name, fingerprint, summary, details_json
                   from ops_alert_events
                """ + parts.where() + JdbcPageSorts.orderBy(pageable, EVENT_SORT_COLUMNS, DEFAULT_EVENT_ORDER)
                + " limit :limit offset :offset";
        parts.params().addValue("limit", pageable.getPageSize());
        parts.params().addValue("offset", pageable.getOffset());
        List<AlertEvent> content = jdbcTemplate.query(sql, parts.params(), EVENT_MAPPER);
        long total = totalElements(
                parts,
                pageable,
                content.size(),
                "select count(*) from ops_alert_events" + parts.where());
        return new PageImpl<>(content, pageable, total);
    }

    public Page<Incident> findIncidents(Pageable pageable, IncidentFilter filter) {
        QueryParts parts = incidentWhere(filter);
        String sql = """
                select id, created_at, updated_at, version, fingerprint, status, severity, title, source,
                       resource_type, resource_name, first_seen_at, last_seen_at, recovered_at,
                       occurrence_count, last_reason, last_event_id
                  from ops_incidents
                """ + parts.where() + JdbcPageSorts.orderBy(pageable, INCIDENT_SORT_COLUMNS, DEFAULT_INCIDENT_ORDER)
                + " limit :limit offset :offset";
        parts.params().addValue("limit", pageable.getPageSize());
        parts.params().addValue("offset", pageable.getOffset());
        List<Incident> content = jdbcTemplate.query(sql, parts.params(), INCIDENT_MAPPER);
        long total = totalElements(
                parts,
                pageable,
                content.size(),
                "select count(*) from ops_incidents" + parts.where());
        return new PageImpl<>(content, pageable, total);
    }

    public Page<HealingAction> findHealingActions(Pageable pageable, HealingActionFilter filter) {
        QueryParts parts = healingActionWhere(filter);
        String sql = """
                select id, incident_id, created_at, updated_at, policy_key, playbook_key, dry_run, status,
                       request_payload, result_payload, started_at, finished_at
                   from ops_healing_actions
                """ + parts.where() + JdbcPageSorts.orderBy(pageable, ACTION_SORT_COLUMNS, DEFAULT_ACTION_ORDER)
                + " limit :limit offset :offset";
        parts.params().addValue("limit", pageable.getPageSize());
        parts.params().addValue("offset", pageable.getOffset());
        List<HealingAction> content = jdbcTemplate.query(sql, parts.params(), ACTION_MAPPER);
        long total = totalElements(
                parts,
                pageable,
                content.size(),
                "select count(*) from ops_healing_actions" + parts.where());
        return new PageImpl<>(content, pageable, total);
    }

    private QueryParts eventWhere(AlertEventFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        if (filter == null) {
            return QueryParts.empty();
        }
        enumEquals(predicates, params, "severity", "severity", filter.severity());
        textEquals(predicates, params, "source", "source", filter.source());
        textEquals(predicates, params, "event_type", "eventType", filter.eventType());
        textEquals(predicates, params, "resource_type", "resourceType", filter.resourceType());
        textLike(predicates, params, "resource_name", "resourceName", filter.resourceName());
        textEquals(predicates, params, "fingerprint", "fingerprint", filter.fingerprint());
        timeRange(predicates, params, "created_at", filter.from(), filter.to());
        return QueryParts.of(predicates, params);
    }

    private QueryParts incidentWhere(IncidentFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        if (filter == null) {
            return QueryParts.empty();
        }
        enumEquals(predicates, params, "status", "status", filter.status());
        enumEquals(predicates, params, "severity", "severity", filter.severity());
        textEquals(predicates, params, "source", "source", filter.source());
        textEquals(predicates, params, "resource_type", "resourceType", filter.resourceType());
        textLike(predicates, params, "resource_name", "resourceName", filter.resourceName());
        textEquals(predicates, params, "fingerprint", "fingerprint", filter.fingerprint());
        timeRange(predicates, params, "last_seen_at", filter.from(), filter.to());
        return QueryParts.of(predicates, params);
    }

    private QueryParts healingActionWhere(HealingActionFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        if (filter == null) {
            return QueryParts.empty();
        }
        if (filter.incidentId() != null) {
            predicates.add("incident_id = :incidentId");
            params.addValue("incidentId", filter.incidentId());
        }
        enumEquals(predicates, params, "status", "status", filter.status());
        textEquals(predicates, params, "policy_key", "policyKey", filter.policyKey());
        textEquals(predicates, params, "playbook_key", "playbookKey", filter.playbookKey());
        if (filter.dryRun() != null) {
            predicates.add("dry_run = :dryRun");
            params.addValue("dryRun", filter.dryRun());
        }
        timeRange(predicates, params, "created_at", filter.from(), filter.to());
        return QueryParts.of(predicates, params);
    }

    private static void enumEquals(
            List<String> predicates, MapSqlParameterSource params, String column, String name, Enum<?> value) {
        if (value != null) {
            predicates.add(column + " = :" + name);
            params.addValue(name, value.name());
        }
    }

    private static void textEquals(
            List<String> predicates, MapSqlParameterSource params, String column, String name, String value) {
        if (StringUtils.hasText(value)) {
            predicates.add("lower(" + column + ") = :" + name);
            params.addValue(name, value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void textLike(
            List<String> predicates, MapSqlParameterSource params, String column, String name, String value) {
        if (StringUtils.hasText(value)) {
            predicates.add("lower(" + column + ") like :" + name);
            params.addValue(name, "%" + value.trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    private static void timeRange(
            List<String> predicates, MapSqlParameterSource params, String column, Instant from, Instant to) {
        if (from != null) {
            predicates.add(column + " >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            predicates.add(column + " <= :to");
            params.addValue("to", Timestamp.from(to));
        }
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private long totalElements(QueryParts parts, Pageable pageable, int contentSize, String countSql) {
        if (parts.filtered()) {
            return count(countSql, parts.params());
        }
        long floor = pageable.getOffset() + contentSize;
        return contentSize >= pageable.getPageSize() ? floor + 1 : floor;
    }

    private static MapSqlParameterSource eventParams(AlertEvent event) {
        return new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("createdAt", timestamp(event.createdAt()))
                .addValue("observedAt", timestamp(event.observedAt()))
                .addValue("source", event.source())
                .addValue("eventType", event.eventType())
                .addValue("severity", event.severity().name())
                .addValue("resourceType", event.resourceType())
                .addValue("resourceName", event.resourceName())
                .addValue("fingerprint", event.fingerprint())
                .addValue("summary", event.summary())
                .addValue("detailsJson", event.detailsJson());
    }

    private static MapSqlParameterSource incidentParams(Incident incident) {
        return new MapSqlParameterSource()
                .addValue("id", incident.id())
                .addValue("createdAt", timestamp(incident.createdAt()))
                .addValue("updatedAt", timestamp(incident.updatedAt()))
                .addValue("version", incident.version())
                .addValue("fingerprint", incident.fingerprint())
                .addValue("status", incident.status().name())
                .addValue("severity", incident.severity().name())
                .addValue("title", incident.title())
                .addValue("source", incident.source())
                .addValue("resourceType", incident.resourceType())
                .addValue("resourceName", incident.resourceName())
                .addValue("firstSeenAt", timestamp(incident.firstSeenAt()))
                .addValue("lastSeenAt", timestamp(incident.lastSeenAt()))
                .addValue("recoveredAt", timestamp(incident.recoveredAt()))
                .addValue("occurrenceCount", incident.occurrenceCount())
                .addValue("lastReason", incident.lastReason())
                .addValue("lastEventId", incident.lastEventId());
    }

    private static MapSqlParameterSource actionParams(HealingAction action) {
        return new MapSqlParameterSource()
                .addValue("id", action.id())
                .addValue("incidentId", action.incidentId())
                .addValue("createdAt", timestamp(action.createdAt()))
                .addValue("updatedAt", timestamp(action.updatedAt()))
                .addValue("policyKey", action.policyKey())
                .addValue("playbookKey", action.playbookKey())
                .addValue("dryRun", action.dryRun())
                .addValue("status", action.status().name())
                .addValue("requestPayload", action.requestPayload())
                .addValue("resultPayload", action.resultPayload())
                .addValue("startedAt", timestamp(action.startedAt()))
                .addValue("finishedAt", timestamp(action.finishedAt()));
    }

    private static AlertEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AlertEvent(
                getUuid(rs, "id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("observed_at")),
                rs.getString("source"),
                rs.getString("event_type"),
                AlertSeverity.valueOf(rs.getString("severity")),
                rs.getString("resource_type"),
                rs.getString("resource_name"),
                rs.getString("fingerprint"),
                rs.getString("summary"),
                rs.getString("details_json"));
    }

    private static Incident mapIncident(ResultSet rs, int rowNum) throws SQLException {
        return new Incident(
                getUuid(rs, "id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                getLong(rs, "version"),
                rs.getString("fingerprint"),
                IncidentStatus.valueOf(rs.getString("status")),
                AlertSeverity.valueOf(rs.getString("severity")),
                rs.getString("title"),
                rs.getString("source"),
                rs.getString("resource_type"),
                rs.getString("resource_name"),
                toInstant(rs.getTimestamp("first_seen_at")),
                toInstant(rs.getTimestamp("last_seen_at")),
                toInstant(rs.getTimestamp("recovered_at")),
                getLong(rs, "occurrence_count"),
                rs.getString("last_reason"),
                getUuid(rs, "last_event_id"));
    }

    private static HealingAction mapAction(ResultSet rs, int rowNum) throws SQLException {
        return new HealingAction(
                getUuid(rs, "id"),
                getUuid(rs, "incident_id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("policy_key"),
                rs.getString("playbook_key"),
                rs.getBoolean("dry_run"),
                HealingActionStatus.valueOf(rs.getString("status")),
                rs.getString("request_payload"),
                rs.getString("result_payload"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static long getLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? 0L : value.longValue();
    }

    private static UUID getUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private record QueryParts(String where, MapSqlParameterSource params) {

        static QueryParts empty() {
            return new QueryParts("", new MapSqlParameterSource());
        }

        boolean filtered() {
            return !where.isBlank();
        }

        static QueryParts of(List<String> predicates, MapSqlParameterSource params) {
            if (predicates.isEmpty()) {
                return empty();
            }
            return new QueryParts(" where " + String.join(" and ", predicates), params);
        }
    }
}
