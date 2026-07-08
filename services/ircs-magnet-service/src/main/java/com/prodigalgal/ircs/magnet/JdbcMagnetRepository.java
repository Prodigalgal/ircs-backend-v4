package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcMagnetRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> OBJECT_LIST = new TypeReference<>() {
    };
    private static final String DEV_SEARCH_SKIPPED_REASON = "DEV_MVP_EXTERNAL_SEARCH_DISABLED";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<MagnetProviderSummary> listProviders() {
        return jdbcTemplate.query(
                """
                select id,
                       code,
                       name,
                       provider_type,
                       base_url,
                       enabled,
                       priority,
                       risk_level,
                       supported_external_ids::text as supported_external_ids,
                       min_delay_ms,
                       max_delay_ms,
                       timeout_ms,
                       result_limit,
                       auto_approve_allowed,
                       content_policy,
                       last_health_check_at,
                       last_health_status,
                       last_error_message,
                       created_at,
                       updated_at
                from magnet_providers
                order by priority asc, code asc
                """,
                providerMapper());
    }

    public Optional<MagnetProviderSummary> findProvider(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id,
                           code,
                           name,
                           provider_type,
                           base_url,
                           enabled,
                           priority,
                           risk_level,
                           supported_external_ids::text as supported_external_ids,
                           min_delay_ms,
                           max_delay_ms,
                           timeout_ms,
                           result_limit,
                           auto_approve_allowed,
                           content_policy,
                           last_health_check_at,
                           last_health_status,
                           last_error_message,
                           created_at,
                           updated_at
                    from magnet_providers
                    where id = ?
                    """,
                    providerMapper(),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public MagnetProviderSummary createProvider(MagnetProviderRequest request) {
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        namedParameterJdbcTemplate.update(
                """
                insert into magnet_providers (
                    id,
                    created_at,
                    updated_at,
                    version,
                    code,
                    name,
                    provider_type,
                    base_url,
                    enabled,
                    priority,
                    risk_level,
                    supported_external_ids,
                    min_delay_ms,
                    max_delay_ms,
                    timeout_ms,
                    result_limit,
                    auto_approve_allowed,
                    content_policy,
                    last_health_check_at,
                    last_health_status,
                    last_error_message
                )
                values (
                    :id,
                    :now,
                    :now,
                    0,
                    :code,
                    :name,
                    :providerType,
                    :baseUrl,
                    :enabled,
                    :priority,
                    :riskLevel,
                    cast(:supportedExternalIds as jsonb),
                    :minDelayMs,
                    :maxDelayMs,
                    :timeoutMs,
                    :resultLimit,
                    :autoApproveAllowed,
                    :contentPolicy,
                    :lastHealthCheckAt,
                    :lastHealthStatus,
                    :lastErrorMessage
                )
                """,
                providerParams(id, now, request));
        return findProvider(id).orElseThrow();
    }

    public Optional<MagnetProviderSummary> updateProvider(UUID id, MagnetProviderRequest request) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = namedParameterJdbcTemplate.update(
                """
                update magnet_providers
                set code = :code,
                    name = :name,
                    provider_type = :providerType,
                    base_url = :baseUrl,
                    enabled = :enabled,
                    priority = :priority,
                    risk_level = :riskLevel,
                    supported_external_ids = cast(:supportedExternalIds as jsonb),
                    min_delay_ms = :minDelayMs,
                    max_delay_ms = :maxDelayMs,
                    timeout_ms = :timeoutMs,
                    result_limit = :resultLimit,
                    auto_approve_allowed = :autoApproveAllowed,
                    content_policy = :contentPolicy,
                    last_health_check_at = :lastHealthCheckAt,
                    last_health_status = :lastHealthStatus,
                    last_error_message = :lastErrorMessage,
                    updated_at = :now,
                    version = coalesce(version, 0) + 1
                where id = :id
                """,
                providerParams(id, now, request));
        if (updated == 0) {
            return Optional.empty();
        }
        return findProvider(id);
    }

    public List<MagnetLinkSummary> findApprovedLinks(UUID unifiedVideoId) {
        return jdbcTemplate.query(
                """
                select id,
                       unified_video_id,
                       provider_code,
                       info_hash,
                       magnet_uri,
                       title,
                       size_bytes,
                       size_label,
                       published_at,
                       seeders,
                       leechers,
                       quality,
                       resolution,
                       matched_external_id_type,
                       matched_external_id_value,
                       match_score,
                       status,
                       source_url,
                       tags::text as tags,
                       created_at,
                       updated_at
                from magnet_links
                where unified_video_id = ?
                  and status = 'APPROVED'
                order by match_score desc nulls last,
                         seeders desc nulls last,
                         updated_at desc nulls last,
                         id asc
                """,
                linkMapper(),
                unifiedVideoId);
    }

    public List<MagnetLinkSummary> findLinks(UUID unifiedVideoId) {
        return jdbcTemplate.query(
                """
                select id,
                       unified_video_id,
                       provider_code,
                       info_hash,
                       magnet_uri,
                       title,
                       size_bytes,
                       size_label,
                       published_at,
                       seeders,
                       leechers,
                       quality,
                       resolution,
                       matched_external_id_type,
                       matched_external_id_value,
                       match_score,
                       status,
                       source_url,
                       tags::text as tags,
                       created_at,
                       updated_at
                from magnet_links
                where unified_video_id = ?
                order by case status
                             when 'APPROVED' then 0
                             when 'CANDIDATE' then 1
                             when 'HIDDEN' then 2
                             when 'REJECTED' then 3
                             else 9
                         end,
                         match_score desc nulls last,
                         seeders desc nulls last,
                         updated_at desc nulls last,
                         id asc
                """,
                linkMapper(),
                unifiedVideoId);
    }

    public boolean existsUnifiedVideo(UUID unifiedVideoId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from unified_videos where id = ?)",
                Boolean.class,
                unifiedVideoId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<MagnetUnifiedVideoSearchTarget> findUnifiedVideoSearchTarget(UUID unifiedVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select uv.id,
                           uv.title,
                           uv.alias_title,
                           uv."year" as release_year,
                           uv.imdb_id,
                           uv.tmdb_id,
                           uv.douban_id,
                           uv.metadata_status
                     from unified_videos uv
                     where uv.id = ?
                     """,
                    (rs, rowNum) -> new MagnetUnifiedVideoSearchTarget(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getString("alias_title"),
                             rs.getString("release_year"),
                             rs.getString("imdb_id"),
                             rs.getString("tmdb_id"),
                             rs.getString("douban_id"),
                             rs.getString("metadata_status")),
                     unifiedVideoId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<MagnetProviderSummary> listEnabledProviders() {
        return jdbcTemplate.query(
                """
                select id,
                       code,
                       name,
                       provider_type,
                       base_url,
                       enabled,
                       priority,
                       risk_level,
                       supported_external_ids::text as supported_external_ids,
                       min_delay_ms,
                       max_delay_ms,
                       timeout_ms,
                       result_limit,
                       auto_approve_allowed,
                       content_policy,
                       last_health_check_at,
                       last_health_status,
                       last_error_message,
                       created_at,
                       updated_at
                from magnet_providers
                where enabled = true
                order by priority asc, code asc
                """,
                providerMapper());
    }

    public List<UUID> findAutoSearchCandidates(int limit, Instant searchedBefore) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Timestamp cutoff = Timestamp.from(searchedBefore == null ? Instant.EPOCH : searchedBefore);
        return namedParameterJdbcTemplate.queryForList(
                """
                select uv.id
                 from unified_videos uv
                 where (
                     length(trim(coalesce(uv.imdb_id, ''))) > 0
                    or length(trim(coalesce(uv.tmdb_id, ''))) > 0
                    or length(trim(coalesce(uv.douban_id, ''))) > 0
                    or length(trim(coalesce(uv.title, ''))) > 0
                     or length(trim(coalesce(uv.alias_title, ''))) > 0
                 )
                  and uv.metadata_status = 'SYNCED'
                  and not exists (
                      select 1
                      from magnet_links ml
                      where ml.unified_video_id = uv.id
                        and ml.status = 'APPROVED'
                  )
                  and not exists (
                      select 1
                      from magnet_search_jobs running
                      where running.unified_video_id = uv.id
                        and running.status = 'RUNNING'
                  )
                  and not exists (
                      select 1
                      from magnet_search_jobs recent
                      where recent.unified_video_id = uv.id
                        and recent.created_at >= :cutoff
                  )
                order by coalesce(uv.updated_at, uv.created_at) desc nulls last,
                         uv.id asc
                limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("cutoff", cutoff)
                        .addValue("limit", safeLimit),
                UUID.class);
    }

    public MagnetSearchJobSummary createRunningSearchJob(
            UUID unifiedVideoId,
            List<MagnetExternalIdQuery> externalIdPlan) {
        return createRunningSearchJob(unifiedVideoId, "ADMIN_MANUAL", externalIdPlan);
    }

    public MagnetSearchJobSummary createRunningSearchJob(
            UUID unifiedVideoId,
            String triggerType,
            List<MagnetExternalIdQuery> externalIdPlan) {
        UUID jobId = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        namedParameterJdbcTemplate.update(
                """
                insert into magnet_search_jobs (
                    id,
                    created_at,
                    updated_at,
                    version,
                    unified_video_id,
                    trigger_type,
                    status,
                    provider_codes,
                    external_id_plan,
                    started_at,
                    finished_at,
                    total_candidates,
                    accepted_count,
                    rejected_count,
                    skipped_reason,
                    error_message
                )
                values (
                    :id,
                    :now,
                    :now,
                    0,
                    :unifiedVideoId,
                    :triggerType,
                    'RUNNING',
                    cast(:providerCodes as jsonb),
                    cast(:externalIdPlan as jsonb),
                    :now,
                    null,
                    0,
                    0,
                    0,
                    null,
                    null
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("now", now)
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("triggerType", triggerType(triggerType))
                        .addValue("providerCodes", toJson(List.of()))
                        .addValue("externalIdPlan", toJson(externalIdPlanJson(externalIdPlan))));
        return findSearchJob(jobId).orElseThrow();
    }

    public MagnetSearchJobSummary createPendingSearchJob(
            UUID unifiedVideoId,
            String triggerType,
            List<MagnetExternalIdQuery> externalIdPlan) {
        UUID jobId = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        namedParameterJdbcTemplate.update(
                """
                insert into magnet_search_jobs (
                    id,
                    created_at,
                    updated_at,
                    version,
                    unified_video_id,
                    trigger_type,
                    status,
                    provider_codes,
                    external_id_plan,
                    started_at,
                    finished_at,
                    total_candidates,
                    accepted_count,
                    rejected_count,
                    skipped_reason,
                    error_message
                )
                values (
                    :id,
                    :now,
                    :now,
                    0,
                    :unifiedVideoId,
                    :triggerType,
                    'PENDING',
                    cast('[]' as jsonb),
                    cast(:externalIdPlan as jsonb),
                    null,
                    null,
                    0,
                    0,
                    0,
                    null,
                    null
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("now", now)
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("triggerType", triggerType(triggerType))
                        .addValue("externalIdPlan", toJson(externalIdPlanJson(externalIdPlan))));
        return findSearchJob(jobId).orElseThrow();
    }

    public Optional<MagnetSearchJobSummary> markSearchJobRunning(
            UUID jobId,
            List<MagnetExternalIdQuery> externalIdPlan) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = namedParameterJdbcTemplate.update(
                """
                update magnet_search_jobs
                set updated_at = :now,
                    status = 'RUNNING',
                    external_id_plan = cast(:externalIdPlan as jsonb),
                    started_at = coalesce(started_at, :now),
                    finished_at = null,
                    skipped_reason = null,
                    error_message = null,
                    version = coalesce(version, 0) + 1
                where id = :jobId
                  and status in ('PENDING', 'RUNNING')
                """,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("now", now)
                        .addValue("externalIdPlan", toJson(externalIdPlanJson(externalIdPlan))));
        return updated == 0 ? Optional.empty() : findSearchJob(jobId);
    }

    public MagnetSearchJobSummary createSkippedSearchJob(UUID unifiedVideoId) {
        return createSkippedSearchJob(unifiedVideoId, DEV_SEARCH_SKIPPED_REASON, List.of(), listEnabledProviderCodes());
    }

    public MagnetSearchJobSummary createSkippedSearchJob(
            UUID unifiedVideoId,
            String skippedReason,
            List<MagnetExternalIdQuery> externalIdPlan,
            List<String> providerCodes) {
        return createSkippedSearchJob(unifiedVideoId, "ADMIN_MANUAL", skippedReason, externalIdPlan, providerCodes);
    }

    public MagnetSearchJobSummary createSkippedSearchJob(
            UUID unifiedVideoId,
            String triggerType,
            String skippedReason,
            List<MagnetExternalIdQuery> externalIdPlan,
            List<String> providerCodes) {
        UUID jobId = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        namedParameterJdbcTemplate.update(
                """
                insert into magnet_search_jobs (
                    id,
                    created_at,
                    updated_at,
                    version,
                    unified_video_id,
                    trigger_type,
                    status,
                    provider_codes,
                    external_id_plan,
                    started_at,
                    finished_at,
                    total_candidates,
                    accepted_count,
                    rejected_count,
                    skipped_reason,
                    error_message
                )
                values (
                    :id,
                    :now,
                    :now,
                    0,
                    :unifiedVideoId,
                    :triggerType,
                    'SKIPPED',
                    cast(:providerCodes as jsonb),
                    cast(:externalIdPlan as jsonb),
                    :now,
                    :now,
                    0,
                    0,
                    0,
                    :skippedReason,
                    null
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("now", now)
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("triggerType", triggerType(triggerType))
                        .addValue("providerCodes", toJson(providerCodes))
                        .addValue("externalIdPlan", toJson(externalIdPlanJson(externalIdPlan)))
                        .addValue("skippedReason", skippedReason));
        return findSearchJob(jobId).orElseThrow();
    }

    public Optional<MagnetSearchJobSummary> markSearchJobSkipped(
            UUID jobId,
            String skippedReason,
            List<MagnetExternalIdQuery> externalIdPlan,
            List<String> providerCodes) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = namedParameterJdbcTemplate.update(
                """
                update magnet_search_jobs
                set updated_at = :now,
                    status = 'SKIPPED',
                    provider_codes = cast(:providerCodes as jsonb),
                    external_id_plan = cast(:externalIdPlan as jsonb),
                    started_at = coalesce(started_at, :now),
                    finished_at = :now,
                    total_candidates = 0,
                    accepted_count = 0,
                    rejected_count = 0,
                    skipped_reason = :skippedReason,
                    error_message = null,
                    version = coalesce(version, 0) + 1
                where id = :jobId
                """,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("now", now)
                        .addValue("providerCodes", toJson(providerCodes))
                        .addValue("externalIdPlan", toJson(externalIdPlanJson(externalIdPlan)))
                        .addValue("skippedReason", skippedReason));
        return updated == 0 ? Optional.empty() : findSearchJob(jobId);
    }

    public Optional<MagnetSearchJobSummary> markSearchJobFailed(UUID jobId, String errorMessage) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = namedParameterJdbcTemplate.update(
                """
                update magnet_search_jobs
                set updated_at = :now,
                    status = 'FAILED',
                    started_at = coalesce(started_at, :now),
                    finished_at = :now,
                    error_message = :errorMessage,
                    version = coalesce(version, 0) + 1
                where id = :jobId
                """,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("now", now)
                        .addValue("errorMessage", errorMessage));
        return updated == 0 ? Optional.empty() : findSearchJob(jobId);
    }

    public void createProviderRun(
            UUID jobId,
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            String status,
            String requestUrl,
            Integer httpStatus,
            int candidateCount,
            int acceptedCount,
            long durationMs,
            String errorMessage) {
        Timestamp now = Timestamp.from(Instant.now());
        namedParameterJdbcTemplate.update(
                """
                insert into magnet_provider_runs (
                    id,
                    created_at,
                    updated_at,
                    version,
                    job_id,
                    provider_id,
                    provider_code,
                    external_id_type,
                    external_id_value,
                    status,
                    request_url,
                    http_status,
                    candidate_count,
                    accepted_count,
                    duration_ms,
                    error_message
                )
                values (
                    :id,
                    :now,
                    :now,
                    0,
                    :jobId,
                    :providerId,
                    :providerCode,
                    :externalIdType,
                    :externalIdValue,
                    :status,
                    :requestUrl,
                    :httpStatus,
                    :candidateCount,
                    :acceptedCount,
                    :durationMs,
                    :errorMessage
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", IrcsUuidGenerators.nextId())
                        .addValue("now", now)
                        .addValue("jobId", jobId)
                        .addValue("providerId", provider.id())
                        .addValue("providerCode", provider.code())
                        .addValue("externalIdType", query.type())
                        .addValue("externalIdValue", query.value())
                        .addValue("status", status)
                        .addValue("requestUrl", requestUrl)
                        .addValue("httpStatus", httpStatus)
                        .addValue("candidateCount", candidateCount)
                        .addValue("acceptedCount", acceptedCount)
                        .addValue("durationMs", durationMs)
                        .addValue("errorMessage", errorMessage));
    }

    public MagnetLinkSummary upsertLink(
            UUID unifiedVideoId,
            MagnetProviderSummary provider,
            MagnetProviderCandidate candidate) {
        UUID linkId = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        String status = Boolean.TRUE.equals(provider.autoApproveAllowed()) ? "APPROVED" : "CANDIDATE";
        MapSqlParameterSource params = linkParams(linkId, now, unifiedVideoId, provider, candidate, status);
        int updated = namedParameterJdbcTemplate.update(
                """
                update magnet_links
                set updated_at = :now,
                    provider_id = :providerId,
                    provider_code = :providerCode,
                    magnet_uri = :magnetUri,
                    title = :title,
                    size_bytes = :sizeBytes,
                    size_label = :sizeLabel,
                    published_at = :publishedAt,
                    seeders = :seeders,
                    leechers = :leechers,
                    quality = :quality,
                    resolution = :resolution,
                    matched_external_id_type = :matchedExternalIdType,
                    matched_external_id_value = :matchedExternalIdValue,
                    match_score = :matchScore,
                    status = case
                        when status in ('REJECTED', 'HIDDEN') then status
                        else :status
                    end,
                    source_url = :sourceUrl,
                    tags = cast(:tags as jsonb),
                    provider_evidence = cast(:providerEvidence as jsonb),
                    last_seen_at = :now,
                    version = coalesce(version, 0) + 1
                where unified_video_id = :unifiedVideoId
                  and info_hash = :infoHash
                """,
                params);
        if (updated == 0) {
            namedParameterJdbcTemplate.update(
                """
                insert into magnet_links (
                    id,
                    created_at,
                    updated_at,
                    version,
                    unified_video_id,
                    provider_id,
                    provider_code,
                    info_hash,
                    magnet_uri,
                    title,
                    size_bytes,
                    size_label,
                    published_at,
                    seeders,
                    leechers,
                    quality,
                    resolution,
                    matched_external_id_type,
                    matched_external_id_value,
                    match_score,
                    status,
                    source_url,
                    tags,
                    provider_evidence,
                    first_seen_at,
                    last_seen_at
                )
                values (
                    :id,
                    :now,
                    :now,
                    0,
                    :unifiedVideoId,
                    :providerId,
                    :providerCode,
                    :infoHash,
                    :magnetUri,
                    :title,
                    :sizeBytes,
                    :sizeLabel,
                    :publishedAt,
                    :seeders,
                    :leechers,
                    :quality,
                    :resolution,
                    :matchedExternalIdType,
                    :matchedExternalIdValue,
                    :matchScore,
                    :status,
                    :sourceUrl,
                    cast(:tags as jsonb),
                    cast(:providerEvidence as jsonb),
                    :now,
                    :now
                )
                """,
                params);
        }
        return findLink(unifiedVideoId, candidate.infoHash()).orElseThrow();
    }

    public Optional<MagnetLinkSummary> updateLinkStatus(UUID unifiedVideoId, UUID linkId, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = namedParameterJdbcTemplate.update(
                """
                update magnet_links
                set updated_at = :now,
                    status = :status,
                    version = coalesce(version, 0) + 1
                where unified_video_id = :unifiedVideoId
                  and id = :linkId
                """,
                new MapSqlParameterSource()
                        .addValue("now", now)
                        .addValue("status", status)
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("linkId", linkId));
        return updated == 0 ? Optional.empty() : findLinkById(unifiedVideoId, linkId);
    }

    public boolean deleteLink(UUID unifiedVideoId, UUID linkId) {
        int updated = namedParameterJdbcTemplate.update(
                """
                delete from magnet_links
                where unified_video_id = :unifiedVideoId
                  and id = :linkId
                """,
                new MapSqlParameterSource()
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("linkId", linkId));
        return updated > 0;
    }

    public MagnetSearchJobSummary finishSearchJob(
            UUID jobId,
            List<String> providerCodes,
            int totalCandidates,
            int acceptedCount,
            int failedRuns) {
        int rejectedCount = Math.max(0, totalCandidates - acceptedCount);
        int successfulRuns = countProviderRuns(jobId, "SUCCESS");
        String status;
        if (failedRuns > 0 && successfulRuns > 0) {
            status = "PARTIAL_SUCCESS";
        } else if (failedRuns > 0) {
            status = "FAILED";
        } else {
            status = "SUCCESS";
        }
        namedParameterJdbcTemplate.update(
                """
                update magnet_search_jobs
                set status = :status,
                    provider_codes = cast(:providerCodes as jsonb),
                    finished_at = :now,
                    total_candidates = :totalCandidates,
                    accepted_count = :acceptedCount,
                    rejected_count = :rejectedCount,
                    skipped_reason = null,
                    error_message = null,
                    updated_at = :now,
                    version = coalesce(version, 0) + 1
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("now", Timestamp.from(Instant.now()))
                        .addValue("status", status)
                        .addValue("providerCodes", toJson(providerCodes))
                        .addValue("totalCandidates", totalCandidates)
                        .addValue("acceptedCount", acceptedCount)
                        .addValue("rejectedCount", rejectedCount));
        return findSearchJob(jobId).orElseThrow();
    }

    public List<MagnetSearchJobSummary> findSearchJobs(UUID unifiedVideoId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return namedParameterJdbcTemplate.query(
                """
                select id,
                       unified_video_id,
                       trigger_type,
                       status,
                       provider_codes::text as provider_codes,
                       external_id_plan::text as external_id_plan,
                       started_at,
                       finished_at,
                       total_candidates,
                       accepted_count,
                       rejected_count,
                       skipped_reason,
                       error_message,
                       created_at,
                       updated_at
                from magnet_search_jobs
                where unified_video_id = :unifiedVideoId
                order by created_at desc, id desc
                limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("limit", safeLimit),
                searchJobMapper());
    }

    public List<MagnetProviderRunSummary> findProviderRuns(UUID jobId) {
        return namedParameterJdbcTemplate.query(
                """
                select id,
                       job_id,
                       provider_id,
                       provider_code,
                       external_id_type,
                       external_id_value,
                       status,
                       request_url,
                       http_status,
                       candidate_count,
                       accepted_count,
                       duration_ms,
                       error_message,
                       created_at,
                       updated_at
                from magnet_provider_runs
                where job_id = :jobId
                order by created_at asc, id asc
                """,
                new MapSqlParameterSource("jobId", jobId),
                providerRunMapper());
    }

    private int countProviderRuns(UUID jobId, String status) {
        Integer count = namedParameterJdbcTemplate.queryForObject(
                """
                select count(*)
                from magnet_provider_runs
                where job_id = :jobId
                  and status = :status
                """,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("status", status),
                Integer.class);
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource linkParams(
            UUID linkId,
            Timestamp now,
            UUID unifiedVideoId,
            MagnetProviderSummary provider,
            MagnetProviderCandidate candidate,
            String status) {
        return new MapSqlParameterSource()
                .addValue("id", linkId)
                .addValue("now", now)
                .addValue("unifiedVideoId", unifiedVideoId)
                .addValue("providerId", provider.id())
                .addValue("providerCode", provider.code())
                .addValue("infoHash", candidate.infoHash())
                .addValue("magnetUri", candidate.magnetUri())
                .addValue("title", candidate.title())
                .addValue("sizeBytes", candidate.sizeBytes())
                .addValue("sizeLabel", candidate.sizeLabel())
                .addValue("publishedAt", toTimestamp(candidate.publishedAt()))
                .addValue("seeders", candidate.seeders())
                .addValue("leechers", candidate.leechers())
                .addValue("quality", candidate.quality())
                .addValue("resolution", candidate.resolution())
                .addValue("matchedExternalIdType", candidate.matchedExternalIdType())
                .addValue("matchedExternalIdValue", candidate.matchedExternalIdValue())
                .addValue("matchScore", candidate.matchScore() == null ? 100 : candidate.matchScore())
                .addValue("status", status)
                .addValue("sourceUrl", candidate.sourceUrl())
                .addValue("tags", toJson(candidate.tags()))
                .addValue("providerEvidence", toJson(candidate.providerEvidence()));
    }

    private MapSqlParameterSource providerParams(UUID id, Timestamp now, MagnetProviderRequest request) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", now)
                .addValue("code", request.code())
                .addValue("name", request.name())
                .addValue("providerType", request.providerType())
                .addValue("baseUrl", request.baseUrl())
                .addValue("enabled", request.enabled())
                .addValue("priority", request.priority())
                .addValue("riskLevel", request.riskLevel())
                .addValue("supportedExternalIds", toJson(request.supportedExternalIds()))
                .addValue("minDelayMs", request.minDelayMs())
                .addValue("maxDelayMs", request.maxDelayMs())
                .addValue("timeoutMs", request.timeoutMs())
                .addValue("resultLimit", request.resultLimit())
                .addValue("autoApproveAllowed", request.autoApproveAllowed())
                .addValue("contentPolicy", request.contentPolicy())
                .addValue("lastHealthCheckAt", toTimestamp(request.lastHealthCheckAt()))
                .addValue("lastHealthStatus", request.lastHealthStatus())
                .addValue("lastErrorMessage", request.lastErrorMessage());
    }

    private List<String> listEnabledProviderCodes() {
        return jdbcTemplate.queryForList(
                """
                select code
                from magnet_providers
                where enabled = true
                order by priority asc, code asc
                """,
                String.class);
    }

    private Optional<MagnetSearchJobSummary> findSearchJob(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id,
                           unified_video_id,
                           trigger_type,
                           status,
                           provider_codes::text as provider_codes,
                           external_id_plan::text as external_id_plan,
                           started_at,
                           finished_at,
                           total_candidates,
                           accepted_count,
                           rejected_count,
                           skipped_reason,
                           error_message,
                           created_at,
                           updated_at
                    from magnet_search_jobs
                    where id = ?
                    """,
                    searchJobMapper(),
                    id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<MagnetLinkSummary> findLink(UUID unifiedVideoId, String infoHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id,
                           unified_video_id,
                           provider_code,
                           info_hash,
                           magnet_uri,
                           title,
                           size_bytes,
                           size_label,
                           published_at,
                           seeders,
                           leechers,
                           quality,
                           resolution,
                           matched_external_id_type,
                           matched_external_id_value,
                           match_score,
                           status,
                           source_url,
                           tags::text as tags,
                           created_at,
                           updated_at
                    from magnet_links
                    where unified_video_id = ?
                      and info_hash = ?
                    """,
                    linkMapper(),
                    unifiedVideoId,
                    infoHash));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<MagnetLinkSummary> findLinkById(UUID unifiedVideoId, UUID linkId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id,
                           unified_video_id,
                           provider_code,
                           info_hash,
                           magnet_uri,
                           title,
                           size_bytes,
                           size_label,
                           published_at,
                           seeders,
                           leechers,
                           quality,
                           resolution,
                           matched_external_id_type,
                           matched_external_id_value,
                           match_score,
                           status,
                           source_url,
                           tags::text as tags,
                           created_at,
                           updated_at
                    from magnet_links
                    where unified_video_id = ?
                      and id = ?
                    """,
                    linkMapper(),
                    unifiedVideoId,
                    linkId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private RowMapper<MagnetProviderSummary> providerMapper() {
        return (rs, rowNum) -> new MagnetProviderSummary(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("provider_type"),
                rs.getString("base_url"),
                getBoolean(rs, "enabled"),
                getInteger(rs, "priority"),
                rs.getString("risk_level"),
                parseStringList(rs.getString("supported_external_ids")),
                getInteger(rs, "min_delay_ms"),
                getInteger(rs, "max_delay_ms"),
                getInteger(rs, "timeout_ms"),
                getInteger(rs, "result_limit"),
                getBoolean(rs, "auto_approve_allowed"),
                rs.getString("content_policy"),
                toInstant(rs.getTimestamp("last_health_check_at")),
                rs.getString("last_health_status"),
                rs.getString("last_error_message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<MagnetLinkSummary> linkMapper() {
        return (rs, rowNum) -> new MagnetLinkSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("unified_video_id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("info_hash"),
                rs.getString("magnet_uri"),
                rs.getString("title"),
                getLong(rs, "size_bytes"),
                rs.getString("size_label"),
                toInstant(rs.getTimestamp("published_at")),
                getInteger(rs, "seeders"),
                getInteger(rs, "leechers"),
                rs.getString("quality"),
                rs.getString("resolution"),
                rs.getString("matched_external_id_type"),
                rs.getString("matched_external_id_value"),
                getInteger(rs, "match_score"),
                rs.getString("status"),
                rs.getString("source_url"),
                parseStringList(rs.getString("tags")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<MagnetSearchJobSummary> searchJobMapper() {
        return (rs, rowNum) -> new MagnetSearchJobSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("unified_video_id", UUID.class),
                rs.getString("trigger_type"),
                rs.getString("status"),
                parseStringList(rs.getString("provider_codes")),
                parseObjectList(rs.getString("external_id_plan")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")),
                getInteger(rs, "total_candidates"),
                getInteger(rs, "accepted_count"),
                getInteger(rs, "rejected_count"),
                rs.getString("skipped_reason"),
                rs.getString("error_message"),
                List.of(),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<MagnetProviderRunSummary> providerRunMapper() {
        return (rs, rowNum) -> new MagnetProviderRunSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("provider_id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("external_id_type"),
                rs.getString("external_id_value"),
                rs.getString("status"),
                rs.getString("request_url"),
                getInteger(rs, "http_status"),
                getInteger(rs, "candidate_count"),
                getInteger(rs, "accepted_count"),
                getLong(rs, "duration_ms"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(unwrapJsonString(json), STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 magnet JSON 数组字段", e);
        }
    }

    private List<Object> parseObjectList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(unwrapJsonString(json), OBJECT_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 magnet JSON 数组字段", e);
        }
    }

    private String unwrapJsonString(String json) throws java.io.IOException {
        String trimmed = json.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return objectMapper.readValue(trimmed, String.class);
        }
        return json;
    }

    private List<Map<String, String>> externalIdPlanJson(List<MagnetExternalIdQuery> externalIdPlan) {
        if (externalIdPlan == null) {
            return List.of();
        }
        return externalIdPlan.stream()
                .map(query -> Map.of(
                        "type", query.type(),
                        "value", query.value()))
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化 magnet JSON 字段", e);
        }
    }

    private Boolean getBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String triggerType(String value) {
        return value == null || value.isBlank() ? "ADMIN_MANUAL" : value.trim();
    }
}
