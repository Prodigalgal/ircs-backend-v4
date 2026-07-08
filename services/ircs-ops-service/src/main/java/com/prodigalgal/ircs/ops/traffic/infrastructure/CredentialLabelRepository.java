package com.prodigalgal.ircs.ops.traffic.infrastructure;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CredentialLabelRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public record CredentialTrafficLabel(
            UUID id,
            String provider,
            String name,
            Integer rateLimit) {

        public String label() {
            if (name == null || name.isBlank()) {
                return "[" + provider + "]";
            }
            return "[" + provider + "] " + name;
        }
    }

    public Optional<String> findLabel(UUID id) {
        String label = DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                select provider, name
                  from sys_credentials
                 where id = :id
                """,
                Map.of("id", id),
                (rs, rowNum) -> {
                    String provider = rs.getString("provider");
                    String name = rs.getString("name");
                    if (name == null || name.isBlank()) {
                        return "[" + provider + "]";
                    }
                    return "[" + provider + "] " + name;
                }));
        return Optional.ofNullable(label);
    }

    public java.util.List<CredentialTrafficLabel> findEnabledProviderLabels(
            String provider,
            String requiredPayloadKey,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, provider, name, rate_limit
                  from sys_credentials
                 where provider = :provider
                   and enabled = true
                """);
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("provider", provider);
        params.put("limit", limit);
        if (requiredPayloadKey != null && !requiredPayloadKey.isBlank()) {
            sql.append(" and jsonb_exists(payload, :requiredPayloadKey)");
            params.put("requiredPayloadKey", requiredPayloadKey);
        }
        sql.append("""
                 order by priority desc nulls last, created_at asc
                 limit :limit
                """);
        return jdbcTemplate.query(
                sql.toString(),
                params,
                (rs, rowNum) -> new CredentialTrafficLabel(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("name"),
                        (Integer) rs.getObject("rate_limit")));
    }
}
