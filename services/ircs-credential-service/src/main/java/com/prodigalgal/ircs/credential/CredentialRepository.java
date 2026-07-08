package com.prodigalgal.ircs.credential;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CredentialRepository {

    private static final String SELECT_COLUMNS = """
            select id, created_at, updated_at, coalesce(version, 0) as version, provider, name, payload,
                   fingerprint, enabled, priority, rate_limit, rate_limit_unit,
                   day_limit, month_limit, class_a_limit, class_b_limit, remark
              from sys_credentials
            """;

    private static final RowMapper<CredentialRecord> ROW_MAPPER = CredentialRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<CredentialRecord> findAll(String provider, Boolean enabled, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(" where 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        if (provider != null) {
            sql.append(" and provider = :provider");
            params.addValue("provider", provider);
        }
        if (enabled != null) {
            sql.append(" and enabled = :enabled");
            params.addValue("enabled", enabled);
        }
        sql.append("""
             order by provider asc, priority desc nulls last, created_at asc
             limit :limit
            """);
        return jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
    }

    public Optional<CredentialRecord> findById(UUID id) {
        String sql = SELECT_COLUMNS + " where id = :id";
        CredentialRecord record = DataAccessUtils.singleResult(
                jdbcTemplate.query(sql, Map.of("id", id), ROW_MAPPER));
        return Optional.ofNullable(record);
    }

    public boolean existsByFingerprint(String fingerprint) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from sys_credentials where fingerprint = :fingerprint)",
                Map.of("fingerprint", fingerprint),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsByFingerprintAndIdNot(String fingerprint, UUID id) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                select exists(
                    select 1 from sys_credentials
                     where fingerprint = :fingerprint
                       and id <> :id
                )
                """,
                Map.of("fingerprint", fingerprint, "id", id),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public CredentialRecord create(CredentialDraft draft) {
        return jdbcTemplate.queryForObject(
                """
                insert into sys_credentials (
                    id, created_at, updated_at, version, provider, name, payload,
                    fingerprint, enabled, priority, rate_limit, rate_limit_unit,
                    day_limit, month_limit, class_a_limit, class_b_limit, remark
                )
                values (
                    :id, now(), now(), 1, :provider, :name, cast(:payloadJson as jsonb),
                    :fingerprint, :enabled, :priority, :rateLimit, :rateLimitUnit,
                    :dayLimit, :monthLimit, :classALimit, :classBLimit, :remark
                )
                returning id, created_at, updated_at, coalesce(version, 0) as version, provider, name, payload,
                          fingerprint, enabled, priority, rate_limit, rate_limit_unit,
                          day_limit, month_limit, class_a_limit, class_b_limit, remark
                """,
                toParams(draft),
                ROW_MAPPER);
    }

    public Optional<CredentialRecord> update(UUID id, CredentialDraft draft) {
        List<CredentialRecord> rows = jdbcTemplate.query(
                """
                update sys_credentials
                   set updated_at = now(),
                       version = coalesce(version, 0) + 1,
                       provider = :provider,
                       name = :name,
                       payload = cast(:payloadJson as jsonb),
                       fingerprint = :fingerprint,
                       enabled = :enabled,
                       priority = :priority,
                       rate_limit = :rateLimit,
                       rate_limit_unit = :rateLimitUnit,
                       day_limit = :dayLimit,
                       month_limit = :monthLimit,
                       class_a_limit = :classALimit,
                       class_b_limit = :classBLimit,
                       remark = :remark
                 where id = :id
                returning id, created_at, updated_at, coalesce(version, 0) as version, provider, name, payload,
                          fingerprint, enabled, priority, rate_limit, rate_limit_unit,
                          day_limit, month_limit, class_a_limit, class_b_limit, remark
                """,
                toParams(draft).addValue("id", id),
                ROW_MAPPER);
        return Optional.ofNullable(DataAccessUtils.singleResult(rows));
    }

    public int deleteById(UUID id) {
        return jdbcTemplate.update("delete from sys_credentials where id = :id", Map.of("id", id));
    }

    public List<CredentialRecord> findEnabledProviderCredentials(
            String provider,
            String requiredPayloadKey,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" where provider = :provider")
                .append(" and enabled = true");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("limit", limit);
        if (requiredPayloadKey != null) {
            sql.append(" and jsonb_exists(payload, :requiredPayloadKey)");
            params.addValue("requiredPayloadKey", requiredPayloadKey);
        }
        sql.append("""
             order by priority desc nulls last, created_at asc
             limit :limit
            """);
        return jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
    }

    private MapSqlParameterSource toParams(CredentialDraft draft) {
        return new MapSqlParameterSource()
                .addValue("id", draft.id())
                .addValue("provider", draft.provider())
                .addValue("name", draft.name())
                .addValue("payloadJson", draft.payloadJson())
                .addValue("fingerprint", draft.fingerprint())
                .addValue("enabled", draft.enabled())
                .addValue("priority", draft.priority())
                .addValue("rateLimit", draft.rateLimit())
                .addValue("rateLimitUnit", draft.rateLimitUnit())
                .addValue("dayLimit", draft.dayLimit())
                .addValue("monthLimit", draft.monthLimit())
                .addValue("classALimit", draft.classALimit())
                .addValue("classBLimit", draft.classBLimit())
                .addValue("remark", draft.remark());
    }

    private static CredentialRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CredentialRecord(
                rs.getObject("id", UUID.class),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getLong("version"),
                rs.getString("provider"),
                rs.getString("name"),
                rs.getString("payload"),
                rs.getString("fingerprint"),
                rs.getBoolean("enabled"),
                (Integer) rs.getObject("priority"),
                (Integer) rs.getObject("rate_limit"),
                rs.getString("rate_limit_unit"),
                (Long) rs.getObject("day_limit"),
                (Long) rs.getObject("month_limit"),
                (Long) rs.getObject("class_a_limit"),
                (Long) rs.getObject("class_b_limit"),
                rs.getString("remark"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
