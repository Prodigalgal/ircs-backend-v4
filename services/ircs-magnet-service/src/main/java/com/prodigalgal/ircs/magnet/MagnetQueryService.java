package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MagnetQueryService {

    private static final String SKIPPED_NO_QUERY = "没有可用于搜刮的外部 ID 或标题";
    private static final String SKIPPED_NO_ENABLED_PROVIDER = "没有已启用的 magnet provider";
    private static final String SKIPPED_NO_SUPPORTED_PROVIDER = "没有支持当前查询类型的 magnet provider";
    private static final String SKIPPED_NOT_ENRICHED = "聚合视频富化未完成，暂不补充磁链";
    private static final String METADATA_STATUS_SYNCED = "SYNCED";

    private final JdbcMagnetRepository magnetRepository;
    private final MagnetProviderSearchRunner providerSearchRunner;
    private final MagnetReadModelCache readModelCache;
    private final MagnetWorkPublisher workPublisher;
    private final SearchSyncWorkPublisher searchSyncWorkPublisher;

    public MagnetQueryService(
            JdbcMagnetRepository magnetRepository,
            MagnetProviderSearchRunner providerSearchRunner,
            MagnetReadModelCache readModelCache,
            ObjectProvider<MagnetWorkPublisher> workPublisherProvider,
            ObjectProvider<SearchSyncWorkPublisher> searchSyncWorkPublisherProvider) {
        this.magnetRepository = magnetRepository;
        this.providerSearchRunner = providerSearchRunner;
        this.readModelCache = readModelCache;
        this.workPublisher = workPublisherProvider == null ? null : workPublisherProvider.getIfAvailable();
        this.searchSyncWorkPublisher = searchSyncWorkPublisherProvider == null
                ? null
                : searchSyncWorkPublisherProvider.getIfAvailable();
    }

    @Transactional(readOnly = true)
    public List<MagnetProviderSummary> listProviders() {
        return readModelCache.listProviders(magnetRepository::listProviders);
    }

    @Transactional(readOnly = true)
    public Optional<MagnetProviderSummary> findProvider(UUID id) {
        return readModelCache.findProvider(id, () -> magnetRepository.findProvider(id));
    }

    @Transactional
    public MagnetProviderSummary createProvider(MagnetProviderRequest request) {
        MagnetProviderRequest normalized = normalizeProviderRequest(request);
        if (normalized.id() != null) {
            throw badRequest("A new magnet provider cannot already have an ID");
        }
        try {
            MagnetProviderSummary result = magnetRepository.createProvider(normalized);
            readModelCache.evictProviders();
            return result;
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Magnet provider code already exists", ex);
        }
    }

    @Transactional
    public MagnetProviderSummary updateProvider(UUID id, MagnetProviderRequest request) {
        MagnetProviderRequest required = requireProviderRequest(request);
        validatePathId(id, required.id());
        MagnetProviderRequest normalized = normalizeProviderRequest(required);
        try {
            MagnetProviderSummary result = magnetRepository.updateProvider(id, normalized)
                    .orElseThrow(() -> notFound("MagnetProvider not found"));
            readModelCache.evictProvider(id);
            return result;
        } catch (DataIntegrityViolationException ex) {
            throw badRequest("Invalid magnet provider payload", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<MagnetLinkSummary> findApprovedLinks(UUID unifiedVideoId) {
        return readModelCache.findApprovedLinks(unifiedVideoId, () -> magnetRepository.findApprovedLinks(unifiedVideoId));
    }

    @Transactional(readOnly = true)
    public List<MagnetLinkSummary> findLinks(UUID unifiedVideoId) {
        ensureUnifiedVideoExists(unifiedVideoId);
        return magnetRepository.findLinks(unifiedVideoId);
    }

    @Transactional(readOnly = true)
    public List<MagnetSearchJobSummary> findSearchJobs(UUID unifiedVideoId, int limit) {
        ensureUnifiedVideoExists(unifiedVideoId);
        return magnetRepository.findSearchJobs(unifiedVideoId, limit);
    }

    @Transactional(readOnly = true)
    public List<MagnetProviderRunSummary> findProviderRuns(UUID jobId) {
        return magnetRepository.findProviderRuns(jobId);
    }

    @Transactional
    public MagnetSearchJobSummary triggerUnifiedSearch(UUID unifiedVideoId) {
        return executeSearch(null, unifiedVideoId, "ADMIN_MANUAL");
    }

    @Transactional
    public MagnetSearchJobSummary triggerAutomaticSearch(UUID unifiedVideoId) {
        return executeSearch(null, unifiedVideoId, "AUTO_SCHEDULED");
    }

    @Transactional
    public MagnetSearchJobSummary enqueueUnifiedSearch(UUID unifiedVideoId) {
        return enqueueSearch(unifiedVideoId, "ADMIN_MANUAL");
    }

    @Transactional
    public MagnetSearchJobSummary enqueueAutomaticSearch(UUID unifiedVideoId) {
        return enqueueSearch(unifiedVideoId, "AUTO_SCHEDULED");
    }

    @Transactional
    public MagnetSearchJobSummary executeQueuedSearch(UUID jobId, UUID unifiedVideoId, String triggerType) {
        return executeSearch(jobId, unifiedVideoId, triggerType);
    }

    @Transactional
    public void markQueuedSearchFailed(UUID jobId, String reason) {
        magnetRepository.markSearchJobFailed(jobId, reason);
    }

    @Transactional
    public MagnetLinkSummary updateLinkStatus(UUID unifiedVideoId, UUID linkId, MagnetLinkStatusRequest request) {
        ensureUnifiedVideoExists(unifiedVideoId);
        String status = normalizeLinkStatus(request == null ? null : request.status());
        MagnetLinkSummary link = magnetRepository.updateLinkStatus(unifiedVideoId, linkId, status)
                .orElseThrow(() -> notFound("Magnet link not found"));
        readModelCache.evictApprovedLinks(unifiedVideoId);
        enqueueSearchSync(unifiedVideoId);
        return link;
    }

    @Transactional
    public void deleteLink(UUID unifiedVideoId, UUID linkId) {
        ensureUnifiedVideoExists(unifiedVideoId);
        if (!magnetRepository.deleteLink(unifiedVideoId, linkId)) {
            throw notFound("Magnet link not found");
        }
        readModelCache.evictApprovedLinks(unifiedVideoId);
        enqueueSearchSync(unifiedVideoId);
    }

    private MagnetSearchJobSummary enqueueSearch(UUID unifiedVideoId, String triggerType) {
        MagnetUnifiedVideoSearchTarget target = magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId)
                .orElseThrow(() -> notFound("UnifiedVideo not found"));
        if (!isEnriched(target)) {
            return magnetRepository.createSkippedSearchJob(
                    unifiedVideoId,
                    triggerType,
                    SKIPPED_NOT_ENRICHED,
                    List.of(),
                    List.of());
        }
        List<MagnetExternalIdQuery> queries = planExternalIdQueries(target);
        if (queries.isEmpty()) {
            return magnetRepository.createSkippedSearchJob(unifiedVideoId, triggerType, SKIPPED_NO_QUERY, queries, List.of());
        }
        if (workPublisher == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Magnet runtime work queue is unavailable");
        }
        MagnetSearchJobSummary job = magnetRepository.createPendingSearchJob(unifiedVideoId, triggerType, queries);
        workPublisher.enqueue(job);
        return job;
    }

    private MagnetSearchJobSummary executeSearch(UUID jobId, UUID unifiedVideoId, String triggerType) {
        MagnetUnifiedVideoSearchTarget target = magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId)
                .orElseThrow(() -> notFound("UnifiedVideo not found"));
        if (!isEnriched(target)) {
            return skipSearch(jobId, unifiedVideoId, triggerType, SKIPPED_NOT_ENRICHED, List.of(), List.of());
        }

        List<MagnetExternalIdQuery> queries = planExternalIdQueries(target);
        if (queries.isEmpty()) {
            return skipSearch(jobId, unifiedVideoId, triggerType, SKIPPED_NO_QUERY, queries, List.of());
        }

        List<MagnetProviderSummary> providers = readModelCache.listEnabledProviders(magnetRepository::listEnabledProviders);
        List<String> providerCodes = providers.stream()
                .map(MagnetProviderSummary::code)
                .toList();
        if (providers.isEmpty()) {
            return skipSearch(jobId, unifiedVideoId, triggerType, SKIPPED_NO_ENABLED_PROVIDER, queries, providerCodes);
        }

        List<ProviderQuery> runPlan = providers.stream()
                .flatMap(provider -> queries.stream()
                        .filter(query -> supports(provider, query))
                        .map(query -> new ProviderQuery(provider, query)))
                .toList();
        if (runPlan.isEmpty()) {
            return skipSearch(jobId, unifiedVideoId, triggerType, SKIPPED_NO_SUPPORTED_PROVIDER, queries, providerCodes);
        }

        MagnetSearchJobSummary job = jobId == null
                ? magnetRepository.createRunningSearchJob(unifiedVideoId, triggerType, queries)
                : magnetRepository.markSearchJobRunning(jobId, queries)
                        .orElseThrow(() -> notFound("Magnet search job not found"));
        int totalCandidates = 0;
        int acceptedCount = 0;
        int failedRuns = 0;
        boolean linksTouched = false;
        List<MagnetLinkSummary> acceptedLinks = new ArrayList<>();

        for (ProviderQuery providerQuery : runPlan) {
            Instant started = Instant.now();
            try {
                MagnetProviderSearchResult result = providerSearchRunner.search(
                        providerQuery.provider(),
                        providerQuery.query(),
                        unifiedVideoId);
                List<MagnetProviderCandidate> candidates = result == null || result.candidates() == null
                        ? List.of()
                        : result.candidates();
                List<MagnetLinkSummary> acceptedForRun = new ArrayList<>();
                for (MagnetProviderCandidate candidate : candidates) {
                    if (isUsableCandidate(candidate)) {
                        acceptedForRun.add(magnetRepository.upsertLink(
                                unifiedVideoId,
                                providerQuery.provider(),
                                candidate));
                        linksTouched = true;
                    }
                }
                totalCandidates += candidates.size();
                acceptedCount += acceptedForRun.size();
                acceptedLinks.addAll(acceptedForRun);
                magnetRepository.createProviderRun(
                        job.id(),
                        providerQuery.provider(),
                        providerQuery.query(),
                        "SUCCESS",
                        result == null ? null : result.requestUrl(),
                        result == null ? null : result.httpStatus(),
                        candidates.size(),
                        acceptedForRun.size(),
                        Duration.between(started, Instant.now()).toMillis(),
                        null);
            } catch (MagnetProviderRunnerException ex) {
                failedRuns++;
                magnetRepository.createProviderRun(
                        job.id(),
                        providerQuery.provider(),
                        providerQuery.query(),
                        "FAILED",
                        ex.requestUrl(),
                        ex.httpStatus(),
                        0,
                        0,
                        Duration.between(started, Instant.now()).toMillis(),
                        ex.failureType());
            } catch (Exception ex) {
                failedRuns++;
                magnetRepository.createProviderRun(
                        job.id(),
                        providerQuery.provider(),
                        providerQuery.query(),
                        "FAILED",
                        null,
                        null,
                        0,
                        0,
                        Duration.between(started, Instant.now()).toMillis(),
                        ex.getMessage());
            }
        }
        if (linksTouched) {
            readModelCache.evictApprovedLinks(unifiedVideoId);
            enqueueSearchSync(unifiedVideoId);
        }

        return withLinks(magnetRepository.finishSearchJob(
                        job.id(),
                        providerCodes,
                        totalCandidates,
                        acceptedCount,
                        failedRuns),
                acceptedLinks);
    }

    private MagnetSearchJobSummary skipSearch(
            UUID jobId,
            UUID unifiedVideoId,
            String triggerType,
            String reason,
            List<MagnetExternalIdQuery> queries,
            List<String> providerCodes) {
        if (jobId == null) {
            return magnetRepository.createSkippedSearchJob(unifiedVideoId, triggerType, reason, queries, providerCodes);
        }
        return magnetRepository.markSearchJobSkipped(jobId, reason, queries, providerCodes)
                .orElseThrow(() -> notFound("Magnet search job not found"));
    }

    private List<MagnetExternalIdQuery> planExternalIdQueries(MagnetUnifiedVideoSearchTarget target) {
        List<MagnetExternalIdQuery> queries = new ArrayList<>();
        addQuery(queries, "IMDB", target.imdbId());
        addQuery(queries, "TMDB", target.tmdbId());
        addQuery(queries, "DOUBAN", target.doubanId());
        String title = trimToNull(target.title());
        String year = trimToNull(target.year());
        if (title != null && year != null) {
            addQuery(queries, "TITLE_YEAR", title + " " + year);
        }
        addQuery(queries, "TITLE", title);
        addQuery(queries, "TITLE", target.aliasTitle());
        return queries;
    }

    private boolean supports(MagnetProviderSummary provider, MagnetExternalIdQuery query) {
        if (provider.supportedExternalIds() == null || provider.supportedExternalIds().isEmpty()) {
            return true;
        }
        boolean declared = provider.supportedExternalIds().stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> value.equalsIgnoreCase(query.type()));
        if (declared) {
            return true;
        }
        return isYtsTitleQuery(provider, query);
    }

    private void addQuery(List<MagnetExternalIdQuery> queries, String type, String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return;
        }
        boolean exists = queries.stream()
                .anyMatch(query -> query.type().equalsIgnoreCase(type)
                        && query.value().equalsIgnoreCase(normalized));
        if (!exists) {
            queries.add(new MagnetExternalIdQuery(type, normalized));
        }
    }

    private boolean isYtsTitleQuery(MagnetProviderSummary provider, MagnetExternalIdQuery query) {
        String providerType = trimToNull(provider.providerType());
        return "YTS_BZ".equalsIgnoreCase(providerType)
                && ("TITLE".equalsIgnoreCase(query.type()) || "TITLE_YEAR".equalsIgnoreCase(query.type()));
    }

    private boolean isUsableCandidate(MagnetProviderCandidate candidate) {
        return candidate != null
                && trimToNull(candidate.infoHash()) != null
                && trimToNull(candidate.magnetUri()) != null
                && trimToNull(candidate.title()) != null
                && trimToNull(candidate.matchedExternalIdType()) != null
                && trimToNull(candidate.matchedExternalIdValue()) != null;
    }

    private boolean isEnriched(MagnetUnifiedVideoSearchTarget target) {
        return target != null && METADATA_STATUS_SYNCED.equalsIgnoreCase(trimToNull(target.metadataStatus()));
    }

    private MagnetSearchJobSummary withLinks(MagnetSearchJobSummary summary, List<MagnetLinkSummary> links) {
        return new MagnetSearchJobSummary(
                summary.id(),
                summary.unifiedVideoId(),
                summary.triggerType(),
                summary.status(),
                summary.providerCodes(),
                summary.externalIdPlan(),
                summary.startedAt(),
                summary.finishedAt(),
                summary.totalCandidates(),
                summary.acceptedCount(),
                summary.rejectedCount(),
                summary.skippedReason(),
                summary.errorMessage(),
                links,
                summary.createdAt(),
                summary.updatedAt());
    }

    private MagnetProviderRequest normalizeProviderRequest(MagnetProviderRequest request) {
        MagnetProviderRequest required = requireProviderRequest(request);
        String code = requireText(required.code(), "Provider code is required");
        String name = requireText(required.name(), "Provider name is required");
        String providerType = requireText(required.providerType(), "Provider type is required");
        String baseUrl = requireText(required.baseUrl(), "Provider baseUrl is required");
        Integer minDelayMs = defaultNonNegative(required.minDelayMs(), 1000, "minDelayMs");
        Integer maxDelayMs = defaultNonNegative(required.maxDelayMs(), 3000, "maxDelayMs");
        if (maxDelayMs < minDelayMs) {
            throw badRequest("maxDelayMs must be greater than or equal to minDelayMs");
        }

        return new MagnetProviderRequest(
                required.id(),
                code,
                name,
                providerType,
                baseUrl,
                defaultBoolean(required.enabled(), true),
                defaultNonNegative(required.priority(), 100, "priority"),
                trimToDefault(required.riskLevel(), "HIGH"),
                normalizeStringList(required.supportedExternalIds()),
                minDelayMs,
                maxDelayMs,
                defaultPositive(required.timeoutMs(), 10000, "timeoutMs"),
                defaultPositive(required.resultLimit(), 20, "resultLimit"),
                defaultBoolean(required.autoApproveAllowed(), false),
                trimToNull(required.contentPolicy()),
                required.lastHealthCheckAt(),
                trimToNull(required.lastHealthStatus()),
                trimToNull(required.lastErrorMessage()),
                required.createdAt(),
                required.updatedAt());
    }

    private MagnetProviderRequest requireProviderRequest(MagnetProviderRequest request) {
        if (request == null) {
            throw badRequest("Magnet provider payload is required");
        }
        return request;
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private String normalizeLinkStatus(String value) {
        String normalized = requireText(value, "Magnet link status is required").toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "APPROVED", "CANDIDATE", "REJECTED", "HIDDEN" -> normalized;
            default -> throw badRequest("Unsupported magnet link status");
        };
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw badRequest(message);
        }
        return normalized;
    }

    private String trimToDefault(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Integer defaultNonNegative(Integer value, int defaultValue, String field) {
        int normalized = value == null ? defaultValue : value;
        if (normalized < 0) {
            throw badRequest(field + " must not be negative");
        }
        return normalized;
    }

    private Integer defaultPositive(Integer value, int defaultValue, String field) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw badRequest(field + " must be positive");
        }
        return normalized;
    }

    private void validatePathId(UUID pathId, UUID bodyId) {
        if (bodyId != null && !Objects.equals(pathId, bodyId)) {
            throw badRequest("Invalid ID");
        }
    }

    private void ensureUnifiedVideoExists(UUID unifiedVideoId) {
        if (!magnetRepository.existsUnifiedVideo(unifiedVideoId)) {
            throw notFound("UnifiedVideo not found");
        }
    }

    private void enqueueSearchSync(UUID unifiedVideoId) {
        if (searchSyncWorkPublisher != null && unifiedVideoId != null) {
            searchSyncWorkPublisher.enqueue(unifiedVideoId, SearchEntityType.UNIFIED_VIDEO, SyncOperation.INDEX);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException badRequest(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message, cause);
    }

    private ResponseStatusException conflict(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record ProviderQuery(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query
    ) {
    }
}
