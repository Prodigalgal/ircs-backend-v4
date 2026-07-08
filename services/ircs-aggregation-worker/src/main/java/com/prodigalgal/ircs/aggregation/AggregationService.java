package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkPublisher;
import com.prodigalgal.ircs.contracts.aggregation.AggregationMaintenanceRunResponse;
import com.prodigalgal.ircs.contracts.aggregation.AggregationResetStepResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AggregationService {

    private final JdbcAggregationRepository repository;
    private final AggregationSearchSyncPublisher publisher;
    private final AggregationStorageCommandPublisher storageCommandPublisher;
    private final RawVideoGraphClusterer rawVideoGraphClusterer;
    private final AggregationPipelineRuntime aggregationPipelineRuntime;
    private final AggregationMatchKeyLock matchKeyLock;
    private final AggregationWorkPublisher workPublisher;

    @Transactional
    public AggregationResult aggregateOne(UUID rawVideoId) {
        return aggregateOneInternal(rawVideoId);
    }

    @Transactional
    public AggregationResult aggregateRuntimeWork(UUID rawVideoId) {
        return aggregateRuntimeWorkBatch(List.of(rawVideoId)).stream()
                .filter(result -> rawVideoId.equals(result.rawVideoId()))
                .findFirst()
                .orElseGet(() -> AggregationResult.skipped(rawVideoId, "MISSING_RAW_VIDEO"));
    }

    @Transactional
    public List<AggregationResult> aggregateRuntimeWorkBatch(List<UUID> rawVideoIds) {
        List<UUID> orderedIds = rawVideoIds == null
                ? List.of()
                : rawVideoIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, AggregationResult> results = new LinkedHashMap<>();
        List<RawVideoAggregationRecord> rawVideos = loadRawVideos(orderedIds, results);
        if (rawVideos.size() <= 1) {
            for (RawVideoAggregationRecord rawVideo : rawVideos) {
                AggregationResult result = bindRawVideo(rawVideo);
                results.put(rawVideo.id(), publishAggregationResult(result));
            }
            return orderedResults(orderedIds, results);
        }

        List<RawVideoAggregationRecord> eligibleRawVideos = new ArrayList<>();
        for (RawVideoAggregationRecord rawVideo : rawVideos) {
            if (rawVideo.isAggregationEligible()) {
                eligibleRawVideos.add(rawVideo);
            } else {
                repository.markPending(rawVideo.id());
                results.put(rawVideo.id(), AggregationResult.skipped(rawVideo.id(), "NOT_ELIGIBLE"));
            }
        }
        if (eligibleRawVideos.isEmpty()) {
            return orderedResults(orderedIds, results);
        }

        List<UnifiedVideoAggregationCandidate> contextUnifiedCandidates =
                repository.findContextUnifiedCandidates(eligibleRawVideos);
        for (RawVideoAggregationCluster cluster : rawVideoGraphClusterer.cluster(
                eligibleRawVideos,
                contextUnifiedCandidates)) {
            if (!cluster.isMultiRawCluster()) {
                AggregationResult result = bindRawVideo(cluster.leader());
                results.put(cluster.leader().id(), publishAggregationResult(result));
                continue;
            }
            BoundClusterResult result = bindRawCluster(cluster);
            recordClusterResults(results, result);
        }
        return orderedResults(orderedIds, results);
    }

    private List<RawVideoAggregationRecord> loadRawVideos(
            List<UUID> orderedIds,
            Map<UUID, AggregationResult> results) {
        if (orderedIds.size() <= 1) {
            return loadRawVideosOneByOne(orderedIds, results);
        }
        List<RawVideoAggregationRecord> loaded = repository.findRawVideos(orderedIds);
        if (loaded == null) {
            return loadRawVideosOneByOne(orderedIds, results);
        }
        Map<UUID, RawVideoAggregationRecord> rawById = new LinkedHashMap<>();
        for (RawVideoAggregationRecord rawVideo : loaded) {
            rawById.putIfAbsent(rawVideo.id(), rawVideo);
        }
        List<RawVideoAggregationRecord> rawVideos = new ArrayList<>(orderedIds.size());
        for (UUID rawVideoId : orderedIds) {
            RawVideoAggregationRecord rawVideo = rawById.get(rawVideoId);
            if (rawVideo == null) {
                results.put(rawVideoId, AggregationResult.skipped(rawVideoId, "MISSING_RAW_VIDEO"));
            } else {
                rawVideos.add(rawVideo);
            }
        }
        return rawVideos;
    }

    private List<RawVideoAggregationRecord> loadRawVideosOneByOne(
            List<UUID> orderedIds,
            Map<UUID, AggregationResult> results) {
        List<RawVideoAggregationRecord> rawVideos = new ArrayList<>(orderedIds.size());
        for (UUID rawVideoId : orderedIds) {
            Optional<RawVideoAggregationRecord> rawVideo = repository.findRawVideo(rawVideoId);
            if (rawVideo.isPresent()) {
                rawVideos.add(rawVideo.orElseThrow());
            } else {
                results.put(rawVideoId, AggregationResult.skipped(rawVideoId, "MISSING_RAW_VIDEO"));
            }
        }
        return rawVideos;
    }

    private AggregationResult aggregateOneInternal(UUID rawVideoId) {
        return repository.findRawVideo(rawVideoId)
                .map(this::bindRawVideo)
                .orElseGet(() -> AggregationResult.skipped(rawVideoId, "MISSING_RAW_VIDEO"));
    }

    @Transactional
    public int recalculateUnified(UUID unifiedVideoId) {
        List<UUID> rawVideoIds = repository.findRawIdsForUnified(unifiedVideoId);
        int processed = 0;
        boolean allSucceeded = true;
        boolean dirtyAlreadyMarked = false;
        for (UUID rawVideoId : rawVideoIds) {
            AggregationResult result = aggregateOne(rawVideoId);
            if ("BOUND".equals(result.status())) {
                publisher.publishRawIndex(rawVideoId);
                result.deletedUnifiedVideoIds().forEach(publisher::publishUnifiedDelete);
                processed++;
            } else {
                allSucceeded = false;
                if ("PIPELINE_FAILED".equals(result.status())) {
                    dirtyAlreadyMarked = true;
                    result.deletedUnifiedVideoIds().forEach(publisher::publishUnifiedDelete);
                }
            }
        }
        if (allSucceeded) {
            repository.markUnifiedSynced(unifiedVideoId);
            publisher.publishUnifiedIndex(unifiedVideoId);
        } else if (!dirtyAlreadyMarked) {
            repository.markUnifiedDirty(unifiedVideoId);
        }
        return processed;
    }

    @Transactional
    public AggregationMaintenanceRunResponse recalculateDirtyUnified(int limit) {
        List<UUID> unifiedVideoIds = repository.findDirtyUnifiedIds(Math.max(1, limit));
        int processed = 0;
        for (UUID unifiedVideoId : unifiedVideoIds) {
            processed += recalculateUnified(unifiedVideoId);
        }
        return new AggregationMaintenanceRunResponse(
                "unified-recalculate",
                unifiedVideoIds.size(),
                processed,
                unifiedVideoIds);
    }

    @Transactional
    public AggregationMaintenanceRunResponse enqueuePendingRawWork(int limit) {
        List<UUID> rawVideoIds = repository.findPendingRawIdsWithoutRuntimeProgress(Math.max(1, limit));
        for (UUID rawVideoId : rawVideoIds) {
            workPublisher.enqueue(rawVideoId, null, "aggregation-pending-backfill");
        }
        return new AggregationMaintenanceRunResponse(
                "aggregation-pending-backfill",
                rawVideoIds.size(),
                rawVideoIds.size(),
                rawVideoIds);
    }

    @Transactional
    public AggregationMaintenanceRunResponse backfillUnifiedCovers(int limit) {
        List<UnifiedCoverBackfillResult> backfilled = repository.backfillUnifiedCoverImagesFromRaw(Math.max(1, limit));
        for (UnifiedCoverBackfillResult result : backfilled) {
            if (result.shouldPromoteToR2()) {
                storageCommandPublisher.enqueueCoverR2Sync(result.coverImageId());
            }
            publisher.publishUnifiedIndex(result.unifiedVideoId());
        }
        return new AggregationMaintenanceRunResponse(
                "aggregation-cover-backfill",
                backfilled.size(),
                backfilled.size(),
                backfilled.stream()
                        .map(UnifiedCoverBackfillResult::unifiedVideoId)
                        .toList());
    }

    @Transactional
    public AggregationMaintenanceRunResponse backfillUnifiedAdultAssessments(int limit) {
        return backfillUnifiedAdultAssessments(limit, true);
    }

    @Transactional
    public AggregationMaintenanceRunResponse backfillUnifiedAdultAssessments(int limit, boolean publishSearch) {
        List<UUID> unifiedVideoIds = repository.findAdultAssessmentBackfillIds(Math.max(1, limit));
        List<UUID> processedIds = repository.rebuildUnifiedAdultAssessments(unifiedVideoIds);
        if (publishSearch) {
            processedIds.forEach(publisher::publishUnifiedIndex);
        }
        return new AggregationMaintenanceRunResponse(
                "aggregation-adult-assessment-backfill",
                unifiedVideoIds.size(),
                processedIds.size(),
                processedIds);
    }

    @Transactional
    public ManualUnifiedMergeResponse mergeUnifiedVideos(List<UUID> unifiedVideoIds) {
        List<UUID> orderedIds = normalizeManualMergeIds(unifiedVideoIds);
        UUID rootUnifiedVideoId = orderedIds.getFirst();
        List<UUID> victimUnifiedVideoIds = orderedIds.subList(1, orderedIds.size());

        repository.mergeDuplicateUnifiedVideos(rootUnifiedVideoId, victimUnifiedVideoIds);
        AggregationPipelineExecution execution = aggregationPipelineRuntime.run(rootUnifiedVideoId);
        List<UUID> rawVideoIds = repository.findRawIdsForUnified(rootUnifiedVideoId);
        if (execution.successful()) {
            repository.markUnifiedSynced(rootUnifiedVideoId);
            publisher.publishUnifiedIndex(rootUnifiedVideoId);
            rawVideoIds.forEach(publisher::publishRawIndex);
            victimUnifiedVideoIds.forEach(publisher::publishUnifiedDelete);
            execution.coverR2SyncImageIds().forEach(storageCommandPublisher::enqueueCoverR2Sync);
            return new ManualUnifiedMergeResponse(
                    rootUnifiedVideoId,
                    List.copyOf(victimUnifiedVideoIds),
                    List.copyOf(rawVideoIds),
                    "MERGED",
                    null);
        }

        repository.markUnifiedDirty(rootUnifiedVideoId);
        victimUnifiedVideoIds.forEach(publisher::publishUnifiedDelete);
        return new ManualUnifiedMergeResponse(
                rootUnifiedVideoId,
                List.copyOf(victimUnifiedVideoIds),
                List.copyOf(rawVideoIds),
                "PIPELINE_FAILED",
                execution.failures());
    }

    public int resetStuckProcessing(int timeoutMinutes, int limit) {
        return repository.resetStuckProcessing(Math.max(1, timeoutMinutes), Math.max(1, limit));
    }

    @Transactional
    public AggregationResetStepResponse prepareAggregationReset(int sampleLimit) {
        int safeLimit = Math.max(1, sampleLimit);
        List<UUID> sampleRawVideoIds = repository.sampleRawVideoIds(safeLimit);
        long rawVideoCount = repository.countRawVideos();
        long unifiedVideoCount = repository.countUnifiedVideos();
        long bindingCount = repository.countRawUnifiedBindings();
        long changedRows = repository.deleteAllAggregationResetOwnedRows();
        return new AggregationResetStepResponse(
                "aggregation-reset",
                "prepare",
                rawVideoCount,
                unifiedVideoCount,
                bindingCount,
                changedRows,
                sampleRawVideoIds);
    }

    @Transactional
    public AggregationResetStepResponse markAllRawAggregationPending(int sampleLimit) {
        int safeLimit = Math.max(1, sampleLimit);
        List<UUID> sampleRawVideoIds = repository.sampleRawVideoIds(safeLimit);
        long rawVideoCount = repository.countRawVideos();
        int changedRows = repository.markAllRawAggregationPending();
        return new AggregationResetStepResponse(
                "aggregation-reset",
                "mark-raw-pending",
                rawVideoCount,
                0,
                0,
                changedRows,
                sampleRawVideoIds);
    }

    private AggregationResult bindRawVideo(RawVideoAggregationRecord rawVideo) {
        if (!rawVideo.isAggregationEligible()) {
            repository.markPending(rawVideo.id());
            return AggregationResult.skipped(rawVideo.id(), "NOT_ELIGIBLE");
        }
        Optional<AggregationMatchKeyLock.MatchLockScope> lockScope =
                matchKeyLock.tryAcquire(AggregationMatchKeys.forRawVideo(rawVideo));
        if (lockScope.isEmpty()) {
            repository.markPending(rawVideo.id());
            return AggregationResult.skipped(rawVideo.id(), "MATCH_LOCK_BUSY");
        }

        try (AggregationMatchKeyLock.MatchLockScope ignored = lockScope.orElseThrow()) {
            AggregationMatchPlan matchPlan = repository.findExistingBinding(rawVideo.id())
                    .map(AggregationMatchPlan::rootOnly)
                    .orElseGet(() -> repository.findMatchPlan(rawVideo));
            UUID unifiedVideoId = matchPlan.hasRoot() ? matchPlan.rootUnifiedVideoId() : repository.createUnifiedVideo(rawVideo);
            if (matchPlan.hasVictims()) {
                repository.mergeDuplicateUnifiedVideos(unifiedVideoId, matchPlan.victimUnifiedVideoIds());
            }

            repository.upsertUnifiedFields(unifiedVideoId, rawVideo);
            repository.bindRawToUnified(rawVideo.id(), unifiedVideoId);
            AggregationPipelineExecution execution = aggregationPipelineRuntime.run(unifiedVideoId);
            if (!execution.successful()) {
                repository.markUnifiedDirty(unifiedVideoId);
                repository.markPending(rawVideo.id());
                log.warn(
                        "Raw video aggregation pipeline failed; rawVideoId={}, unifiedVideoId={}, failures={}",
                        rawVideo.id(),
                        unifiedVideoId,
                        execution.failures());
                return AggregationResult.pipelineFailed(rawVideo.id(), unifiedVideoId, matchPlan.victimUnifiedVideoIds());
            }
            execution.coverR2SyncImageIds().forEach(storageCommandPublisher::enqueueCoverR2Sync);
            repository.markBound(rawVideo.id());

            log.info(
                    "Raw video aggregated: rawVideoId={}, unifiedVideoId={}, deletedUnifiedVideoIds={}",
                    rawVideo.id(),
                    unifiedVideoId,
                    matchPlan.victimUnifiedVideoIds());
            return AggregationResult.bound(rawVideo.id(), unifiedVideoId, matchPlan.victimUnifiedVideoIds());
        }
    }

    private BoundClusterResult bindRawCluster(RawVideoAggregationCluster cluster) {
        List<RawVideoAggregationRecord> rawVideos = cluster.members().stream()
                .filter(RawVideoAggregationRecord::isAggregationEligible)
                .toList();
        if (rawVideos.isEmpty()) {
            return BoundClusterResult.skipped(cluster.rawVideoIds(), "NOT_ELIGIBLE");
        }
        Optional<AggregationMatchKeyLock.MatchLockScope> lockScope =
                matchKeyLock.tryAcquire(AggregationMatchKeys.forCluster(cluster));
        if (lockScope.isEmpty()) {
            rawVideos.forEach(rawVideo -> repository.markPending(rawVideo.id()));
            return BoundClusterResult.skipped(cluster.rawVideoIds(), "MATCH_LOCK_BUSY");
        }

        try (AggregationMatchKeyLock.MatchLockScope ignored = lockScope.orElseThrow()) {
            AggregationMatchPlan matchPlan = findClusterMatchPlan(cluster, rawVideos);
            UUID unifiedVideoId = matchPlan.hasRoot()
                    ? matchPlan.rootUnifiedVideoId()
                    : repository.createUnifiedVideo(cluster.leader());
            if (matchPlan.hasVictims()) {
                repository.mergeDuplicateUnifiedVideos(unifiedVideoId, matchPlan.victimUnifiedVideoIds());
            }

            for (RawVideoAggregationRecord rawVideo : rawVideos) {
                repository.upsertUnifiedFields(unifiedVideoId, rawVideo);
                repository.bindRawToUnified(rawVideo.id(), unifiedVideoId);
            }
            AggregationPipelineExecution execution = aggregationPipelineRuntime.run(unifiedVideoId);
            if (!execution.successful()) {
                repository.markUnifiedDirty(unifiedVideoId);
                rawVideos.forEach(rawVideo -> repository.markPending(rawVideo.id()));
                log.warn(
                        "Raw video cluster aggregation pipeline failed; rawVideoIds={}, unifiedVideoId={}, failures={}",
                        cluster.rawVideoIds(),
                        unifiedVideoId,
                        execution.failures());
                return BoundClusterResult.pipelineFailed(
                        cluster.rawVideoIds(),
                        unifiedVideoId,
                        matchPlan.victimUnifiedVideoIds());
            }
            execution.coverR2SyncImageIds().forEach(storageCommandPublisher::enqueueCoverR2Sync);
            for (RawVideoAggregationRecord rawVideo : rawVideos) {
                repository.markBound(rawVideo.id());
            }

            log.info(
                    "Raw video cluster aggregated: rawVideoIds={}, unifiedVideoId={}, deletedUnifiedVideoIds={}",
                    cluster.rawVideoIds(),
                    unifiedVideoId,
                    matchPlan.victimUnifiedVideoIds());
            return BoundClusterResult.bound(cluster.rawVideoIds(), unifiedVideoId, matchPlan.victimUnifiedVideoIds());
        }
    }

    private AggregationMatchPlan findClusterMatchPlan(
            RawVideoAggregationCluster cluster,
            List<RawVideoAggregationRecord> rawVideos) {
        List<UUID> matchedUnifiedIds = new java.util.ArrayList<>();
        matchedUnifiedIds.addAll(cluster.contextUnifiedVideoIds());
        for (RawVideoAggregationRecord rawVideo : leaderFirst(cluster, rawVideos)) {
            repository.findExistingBinding(rawVideo.id()).ifPresent(matchedUnifiedIds::add);
            AggregationMatchPlan plan = repository.findMatchPlan(rawVideo);
            if (plan.hasRoot()) {
                matchedUnifiedIds.add(plan.rootUnifiedVideoId());
            }
            matchedUnifiedIds.addAll(plan.victimUnifiedVideoIds());
        }
        List<UUID> distinctMatches = matchedUnifiedIds.stream()
                .distinct()
                .toList();
        if (distinctMatches.isEmpty()) {
            return AggregationMatchPlan.none();
        }
        if (distinctMatches.size() == 1) {
            return AggregationMatchPlan.rootOnly(distinctMatches.getFirst());
        }
        return AggregationMatchPlan.rootWithVictims(distinctMatches.getFirst(), distinctMatches.subList(1, distinctMatches.size()));
    }

    private List<RawVideoAggregationRecord> leaderFirst(
            RawVideoAggregationCluster cluster,
            List<RawVideoAggregationRecord> rawVideos) {
        java.util.LinkedHashSet<RawVideoAggregationRecord> ordered = new java.util.LinkedHashSet<>();
        ordered.add(cluster.leader());
        ordered.addAll(rawVideos);
        return List.copyOf(ordered);
    }

    private void publishBoundResult(AggregationResult result) {
        publisher.publishUnifiedIndex(result.unifiedVideoId());
        publisher.publishRawIndex(result.rawVideoId());
        result.deletedUnifiedVideoIds().forEach(publisher::publishUnifiedDelete);
    }

    private AggregationResult publishAggregationResult(AggregationResult result) {
        if ("BOUND".equals(result.status())) {
            publishBoundResult(result);
        } else if ("PIPELINE_FAILED".equals(result.status())) {
            result.deletedUnifiedVideoIds().forEach(publisher::publishUnifiedDelete);
        }
        return result;
    }

    private void publishClusterResult(BoundClusterResult result) {
        publisher.publishUnifiedIndex(result.unifiedVideoId());
        result.rawVideoIds().forEach(publisher::publishRawIndex);
        result.deletedUnifiedVideoIds().forEach(publisher::publishUnifiedDelete);
    }

    private void recordClusterResults(Map<UUID, AggregationResult> results, BoundClusterResult result) {
        if ("BOUND".equals(result.status())) {
            publishClusterResult(result);
            result.rawVideoIds().forEach(rawVideoId -> results.put(
                    rawVideoId,
                    AggregationResult.bound(rawVideoId, result.unifiedVideoId(), result.deletedUnifiedVideoIds())));
            return;
        }
        if ("PIPELINE_FAILED".equals(result.status())) {
            result.deletedUnifiedVideoIds().forEach(publisher::publishUnifiedDelete);
            result.rawVideoIds().forEach(rawVideoId -> results.put(
                    rawVideoId,
                    AggregationResult.pipelineFailed(rawVideoId, result.unifiedVideoId(), result.deletedUnifiedVideoIds())));
            return;
        }
        result.rawVideoIds().forEach(rawVideoId -> results.put(
                rawVideoId,
                AggregationResult.skipped(rawVideoId, result.reason())));
    }

    private List<AggregationResult> orderedResults(
            List<UUID> orderedIds,
            Map<UUID, AggregationResult> results) {
        return orderedIds.stream()
                .map(results::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<UUID> normalizeManualMergeIds(List<UUID> unifiedVideoIds) {
        if (unifiedVideoIds == null) {
            throw new IllegalArgumentException("unifiedVideoIds must contain at least two IDs");
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(unifiedVideoIds.stream()
                .filter(Objects::nonNull)
                .toList()));
        if (ids.size() < 2) {
            throw new IllegalArgumentException("unifiedVideoIds must contain at least two distinct IDs");
        }
        return ids;
    }

    private record BoundClusterResult(
            List<UUID> rawVideoIds,
            UUID unifiedVideoId,
            String status,
            List<UUID> deletedUnifiedVideoIds,
            String reason) {

        static BoundClusterResult bound(
                List<UUID> rawVideoIds,
                UUID unifiedVideoId,
                List<UUID> deletedUnifiedVideoIds) {
            return new BoundClusterResult(
                    List.copyOf(rawVideoIds),
                    unifiedVideoId,
                    "BOUND",
                    List.copyOf(deletedUnifiedVideoIds),
                    null);
        }

        static BoundClusterResult skipped(List<UUID> rawVideoIds, String reason) {
            return new BoundClusterResult(List.copyOf(rawVideoIds), null, reason, List.of(), reason);
        }

        static BoundClusterResult pipelineFailed(
                List<UUID> rawVideoIds,
                UUID unifiedVideoId,
                List<UUID> deletedUnifiedVideoIds) {
            return new BoundClusterResult(
                    List.copyOf(rawVideoIds),
                    unifiedVideoId,
                    "PIPELINE_FAILED",
                    List.copyOf(deletedUnifiedVideoIds),
                    null);
        }
    }
}
