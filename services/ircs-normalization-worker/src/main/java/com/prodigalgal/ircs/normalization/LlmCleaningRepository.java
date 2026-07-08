package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
class LlmCleaningRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    Optional<LlmCleaningCandidate> findCandidate(LlmCleaningKind kind, UUID rawId) {
        if (rawId == null) {
            return Optional.empty();
        }
        List<LlmCleaningCandidate> candidates = jdbcTemplate.query(
                candidateSql(kind),
                new MapSqlParameterSource("rawId", rawId),
                (rs, rowNum) -> new LlmCleaningCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("raw_value")));
        return candidates.stream().findFirst();
    }

    List<LlmCleaningStandard> findStandards(LlmCleaningKind kind) {
        return jdbcTemplate.query(
                standardSql(kind),
                standardParams(kind),
                (rs, rowNum) -> new LlmCleaningStandard(
                        rs.getObject("id", UUID.class),
                        rs.getString("name")));
    }

    boolean applyMatch(LlmCleaningKind kind, UUID rawId, UUID standardId) {
        requireCategory(kind);
        int updated = jdbcTemplate.update(
                """
                update raw_videos
                   set category_code = (
                           select slug
                             from standard_category
                            where id = :standardId
                            limit 1
                       ),
                       updated_at = now()
                 where id = :rawId
                   and nullif(trim(coalesce(category_code, '')), '') is null
                """,
                new MapSqlParameterSource()
                        .addValue("rawId", rawId)
                        .addValue("standardId", standardId));
        return updated > 0;
    }

    boolean applyNoise(LlmCleaningKind kind, UUID rawId) {
        requireCategory(kind);
        return false;
    }

    private String candidateSql(LlmCleaningKind kind) {
        requireCategory(kind);
        return switch (kind) {
            case CATEGORY -> """
                    select id, source_category_name as raw_value
                    from raw_videos
                    where id = :rawId
                      and nullif(trim(coalesce(category_code, '')), '') is null
                      and source_category_name is not null
                    limit 1
                    """;
            default -> throw unsupportedKind(kind);
        };
    }

    private String standardSql(LlmCleaningKind kind) {
        requireCategory(kind);
        return switch (kind) {
            case CATEGORY -> """
                    select id, name
                    from standard_category
                    where slug in (:allowedCategoryCodes)
                      and name is not null
                    order by name
                    """;
            default -> throw unsupportedKind(kind);
        };
    }

    private void requireCategory(LlmCleaningKind kind) {
        if (kind != LlmCleaningKind.CATEGORY) {
            throw unsupportedKind(kind);
        }
    }

    private Map<String, List<String>> standardParams(LlmCleaningKind kind) {
        requireCategory(kind);
        return Map.of("allowedCategoryCodes", StandardContentCategoryClassifier.stableCategoryCodes());
    }

    private IllegalArgumentException unsupportedKind(LlmCleaningKind kind) {
        return new IllegalArgumentException("Only category LLM cleaning is supported after content metadata flattening: " + kind);
    }

    static String safeValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
