package com.prodigalgal.ircs.metadata.pipeline;

import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MetadataPipelineRunRepository {

    private static final String STEP = "ENRICH_METADATA";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PERMANENT_FAILURE = "PERMANENT_FAILURE";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<ProviderType> prepareDispatch(UUID rawVideoId, String pipelineVersion, List<ProviderType> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        String version = normalizeVersion(pipelineVersion);
        jdbcTemplate.update(
                """
                insert into raw_video_pipeline_runs (
                    raw_video_id, pipeline_version, step, status, expected_count,
                    completed_count, success_count, failure_count,
                    retryable_failure_count, permanent_failure_count,
                    created_at, updated_at
                )
                values (
                    :rawVideoId, :pipelineVersion, :step, :status, :expectedCount,
                    0, 0, 0, 0, 0, now(), now()
                )
                on conflict (raw_video_id, pipeline_version, step) do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", version)
                        .addValue("step", STEP)
                        .addValue("status", STATUS_PENDING)
                        .addValue("expectedCount", providers.size()));

        MapSqlParameterSource[] batch = providers.stream()
                .map(provider -> new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", version)
                        .addValue("providerType", provider.name())
                        .addValue("status", STATUS_PENDING))
                .toArray(MapSqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(
                """
                insert into raw_video_enrichment_provider_runs (
                    raw_video_id, pipeline_version, provider_type, status,
                    attempt_count, dispatched_at, created_at, updated_at
                )
                values (
                    :rawVideoId, :pipelineVersion, :providerType, :status,
                    0, now(), now(), now()
                )
                on conflict (raw_video_id, pipeline_version, provider_type) do nothing
                """,
                batch);

        List<String> pendingProviderNames = jdbcTemplate.queryForList(
                """
                select provider_type
                from raw_video_enrichment_provider_runs
                where raw_video_id = :rawVideoId
                  and pipeline_version = :pipelineVersion
                  and provider_type in (:providers)
                  and status = 'PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", version)
                        .addValue("providers", providers.stream().map(Enum::name).toList()),
                String.class);
        return providers.stream()
                .filter(provider -> pendingProviderNames.contains(provider.name()))
                .toList();
    }

    public ProviderCompletion completeProvider(
            UUID rawVideoId,
            String pipelineVersion,
            ProviderType providerType,
            boolean success,
            boolean retryable,
            String errorCode,
            String errorMessage) {
        if (providerType == null) {
            return ProviderCompletion.untracked();
        }
        String version = normalizeVersion(pipelineVersion);
        String providerStatus = success ? STATUS_SUCCESS : retryable ? STATUS_FAILED : STATUS_PERMANENT_FAILURE;
        int changed = jdbcTemplate.update(
                """
                update raw_video_enrichment_provider_runs
                set status = :status,
                    retryable = :retryable,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    attempt_count = attempt_count + 1,
                    completed_at = now(),
                    updated_at = now()
                where raw_video_id = :rawVideoId
                  and pipeline_version = :pipelineVersion
                  and provider_type = :providerType
                  and status = 'PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", version)
                        .addValue("providerType", providerType.name())
                        .addValue("status", providerStatus)
                        .addValue("retryable", retryable)
                        .addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage));
        if (changed == 0) {
            return findCompletion(rawVideoId, version, false).orElse(ProviderCompletion.untracked());
        }

        int successDelta = success ? 1 : 0;
        int retryableFailureDelta = !success && retryable ? 1 : 0;
        int permanentFailureDelta = !success && !retryable ? 1 : 0;
        int failureDelta = retryableFailureDelta + permanentFailureDelta;
        List<ProviderCompletion> completions = jdbcTemplate.query(
                """
                update raw_video_pipeline_runs
                set completed_count = least(expected_count, completed_count + 1),
                    success_count = success_count + :successDelta,
                    failure_count = failure_count + :failureDelta,
                    retryable_failure_count = retryable_failure_count + :retryableFailureDelta,
                    permanent_failure_count = permanent_failure_count + :permanentFailureDelta,
                    updated_at = now()
                where raw_video_id = :rawVideoId
                  and pipeline_version = :pipelineVersion
                  and step = :step
                returning expected_count, completed_count, success_count, failure_count,
                          retryable_failure_count, permanent_failure_count
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", version)
                        .addValue("step", STEP)
                        .addValue("successDelta", successDelta)
                        .addValue("failureDelta", failureDelta)
                        .addValue("retryableFailureDelta", retryableFailureDelta)
                        .addValue("permanentFailureDelta", permanentFailureDelta),
                (rs, rowNum) -> ProviderCompletion.tracked(
                        true,
                        rs.getInt("expected_count"),
                        rs.getInt("completed_count"),
                        rs.getInt("success_count"),
                        rs.getInt("failure_count"),
                        rs.getInt("retryable_failure_count"),
                        rs.getInt("permanent_failure_count")));
        if (completions.isEmpty()) {
            return ProviderCompletion.untracked();
        }
        return completions.get(0);
    }

    public int markRunFinished(UUID rawVideoId, String pipelineVersion, String status) {
        return jdbcTemplate.update(
                """
                update raw_video_pipeline_runs
                set status = :status,
                    updated_at = now()
                where raw_video_id = :rawVideoId
                  and pipeline_version = :pipelineVersion
                  and step = :step
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", normalizeVersion(pipelineVersion))
                        .addValue("step", STEP)
                        .addValue("status", status));
    }

    private Optional<ProviderCompletion> findCompletion(
            UUID rawVideoId,
            String pipelineVersion,
            boolean newlyCompleted) {
        List<ProviderCompletion> completions = jdbcTemplate.query(
                """
                select expected_count, completed_count, success_count, failure_count,
                       retryable_failure_count, permanent_failure_count
                from raw_video_pipeline_runs
                where raw_video_id = :rawVideoId
                  and pipeline_version = :pipelineVersion
                  and step = :step
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("pipelineVersion", pipelineVersion)
                        .addValue("step", STEP),
                (rs, rowNum) -> ProviderCompletion.tracked(
                        newlyCompleted,
                        rs.getInt("expected_count"),
                        rs.getInt("completed_count"),
                        rs.getInt("success_count"),
                        rs.getInt("failure_count"),
                        rs.getInt("retryable_failure_count"),
                        rs.getInt("permanent_failure_count")));
        return completions.stream().findFirst();
    }

    private static String normalizeVersion(String pipelineVersion) {
        return StringUtils.hasText(pipelineVersion) ? pipelineVersion.trim() : "_";
    }

    public record ProviderCompletion(
            boolean tracked,
            boolean newlyCompleted,
            int expectedCount,
            int completedCount,
            int successCount,
            int failureCount,
            int retryableFailureCount,
            int permanentFailureCount) {

        public static ProviderCompletion tracked(
                boolean newlyCompleted,
                int expectedCount,
                int completedCount,
                int successCount,
                int failureCount,
                int retryableFailureCount,
                int permanentFailureCount) {
            return new ProviderCompletion(
                    true,
                    newlyCompleted,
                    expectedCount,
                    completedCount,
                    successCount,
                    failureCount,
                    retryableFailureCount,
                    permanentFailureCount);
        }

        public static ProviderCompletion untracked() {
            return new ProviderCompletion(false, false, 0, 0, 0, 0, 0, 0);
        }

        public boolean allCompleted() {
            return tracked && expectedCount > 0 && completedCount >= expectedCount;
        }
    }
}
