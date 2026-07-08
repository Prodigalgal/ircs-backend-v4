package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkPublisher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AggregationServiceTest {

    @Mock
    private JdbcAggregationRepository repository;

    @Mock
    private AggregationSearchSyncPublisher publisher;

    @Mock
    private AggregationStorageCommandPublisher storageCommandPublisher;

    @Mock
    private RawVideoGraphClusterer rawVideoGraphClusterer;

    @Mock
    private AggregationPipelineRuntime aggregationPipelineRuntime;

    @Mock
    private AggregationMatchKeyLock matchKeyLock;

    @Mock
    private AggregationWorkPublisher workPublisher;

    @InjectMocks
    private AggregationService service;

    @BeforeEach
    void setUp() {
        lenient().when(matchKeyLock.tryAcquire(any()))
                .thenReturn(Optional.of(AggregationMatchKeyLock.MatchLockScope.noop()));
    }

    @Test
    void createsUnifiedVideoBindsRawVideoAndPublishesSearchSync() {
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId);

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(repository.findExistingBinding(rawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(rawVideo)).thenReturn(AggregationMatchPlan.none());
        when(repository.createUnifiedVideo(rawVideo)).thenReturn(unifiedVideoId);
        UUID coverImageId = UUID.randomUUID();
        when(aggregationPipelineRuntime.run(unifiedVideoId))
                .thenReturn(pipelineExecution(unifiedVideoId, List.of(coverImageId)));

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("BOUND", result.status());
        verify(repository).upsertUnifiedFields(unifiedVideoId, rawVideo);
        verify(repository).bindRawToUnified(rawVideoId, unifiedVideoId);
        verify(aggregationPipelineRuntime).run(unifiedVideoId);
        verify(storageCommandPublisher).enqueueCoverR2Sync(coverImageId);
        verify(repository).markBound(rawVideoId);
        verify(publisher).publishUnifiedIndex(unifiedVideoId);
        verify(publisher).publishRawIndex(rawVideoId);
    }

    @Test
    void reusesExistingUnifiedVideoWhenExternalIdMatches() {
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId);

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(repository.findExistingBinding(rawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(rawVideo)).thenReturn(AggregationMatchPlan.rootOnly(unifiedVideoId));
        when(aggregationPipelineRuntime.run(unifiedVideoId))
                .thenReturn(pipelineExecution(unifiedVideoId, List.of()));

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("BOUND", result.status());
        verify(repository, never()).createUnifiedVideo(rawVideo);
        verify(repository).upsertUnifiedFields(unifiedVideoId, rawVideo);
        verify(repository).bindRawToUnified(rawVideoId, unifiedVideoId);
        verify(aggregationPipelineRuntime).run(unifiedVideoId);
        verify(repository).markBound(rawVideoId);
        verify(publisher).publishUnifiedIndex(unifiedVideoId);
        verify(publisher).publishRawIndex(rawVideoId);
    }

    @Test
    void clustersMultipleRawVideosIntoOneUnifiedAndPublishesPerRawSearchSync() {
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        UUID coverImageId = UUID.randomUUID();
        RawVideoAggregationRecord firstRawVideo = readyRawVideo(firstRawVideoId, null);
        RawVideoAggregationRecord secondRawVideo = readyRawVideo(
                secondRawVideoId,
                "Codex Aggregation 1080p",
                "Codex Alias",
                null);
        RawVideoAggregationCluster cluster =
                new RawVideoAggregationCluster(firstRawVideo, List.of(firstRawVideo, secondRawVideo));

        when(repository.findRawVideos(List.of(firstRawVideoId, secondRawVideoId)))
                .thenReturn(List.of(firstRawVideo, secondRawVideo));
        when(repository.findContextUnifiedCandidates(List.of(firstRawVideo, secondRawVideo))).thenReturn(List.of());
        when(rawVideoGraphClusterer.cluster(List.of(firstRawVideo, secondRawVideo), List.of())).thenReturn(List.of(cluster));
        when(repository.findExistingBinding(firstRawVideoId)).thenReturn(Optional.empty());
        when(repository.findExistingBinding(secondRawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(firstRawVideo)).thenReturn(AggregationMatchPlan.none());
        when(repository.findMatchPlan(secondRawVideo)).thenReturn(AggregationMatchPlan.none());
        when(repository.createUnifiedVideo(firstRawVideo)).thenReturn(unifiedVideoId);
        when(aggregationPipelineRuntime.run(unifiedVideoId))
                .thenReturn(pipelineExecution(unifiedVideoId, List.of(coverImageId)));

        List<AggregationResult> results =
                service.aggregateRuntimeWorkBatch(List.of(firstRawVideoId, secondRawVideoId));

        assertEquals(List.of("BOUND", "BOUND"), results.stream().map(AggregationResult::status).toList());
        verify(repository).createUnifiedVideo(firstRawVideo);
        verify(repository).upsertUnifiedFields(unifiedVideoId, firstRawVideo);
        verify(repository).upsertUnifiedFields(unifiedVideoId, secondRawVideo);
        verify(repository).bindRawToUnified(firstRawVideoId, unifiedVideoId);
        verify(repository).bindRawToUnified(secondRawVideoId, unifiedVideoId);
        verify(aggregationPipelineRuntime).run(unifiedVideoId);
        verify(storageCommandPublisher).enqueueCoverR2Sync(coverImageId);
        verify(repository).markBound(firstRawVideoId);
        verify(repository).markBound(secondRawVideoId);
        verify(publisher).publishUnifiedIndex(unifiedVideoId);
        verify(publisher).publishRawIndex(firstRawVideoId);
        verify(publisher).publishRawIndex(secondRawVideoId);
    }

    @Test
    void clusteredRawVideosMergeExistingUnifiedVictimsThroughExistingPath() {
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        UUID rootUnifiedVideoId = UUID.randomUUID();
        UUID victimUnifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord firstRawVideo = readyRawVideo(firstRawVideoId, null);
        RawVideoAggregationRecord secondRawVideo = readyRawVideo(
                secondRawVideoId,
                "Codex Aggregation 1080p",
                "Codex Alias",
                null);
        RawVideoAggregationCluster cluster =
                new RawVideoAggregationCluster(firstRawVideo, List.of(firstRawVideo, secondRawVideo));

        when(repository.findRawVideos(List.of(firstRawVideoId, secondRawVideoId)))
                .thenReturn(List.of(firstRawVideo, secondRawVideo));
        when(repository.findContextUnifiedCandidates(List.of(firstRawVideo, secondRawVideo))).thenReturn(List.of());
        when(rawVideoGraphClusterer.cluster(List.of(firstRawVideo, secondRawVideo), List.of())).thenReturn(List.of(cluster));
        when(repository.findExistingBinding(firstRawVideoId)).thenReturn(Optional.of(rootUnifiedVideoId));
        when(repository.findExistingBinding(secondRawVideoId)).thenReturn(Optional.of(victimUnifiedVideoId));
        when(repository.findMatchPlan(firstRawVideo)).thenReturn(AggregationMatchPlan.rootOnly(rootUnifiedVideoId));
        when(repository.findMatchPlan(secondRawVideo)).thenReturn(AggregationMatchPlan.rootOnly(victimUnifiedVideoId));
        when(aggregationPipelineRuntime.run(rootUnifiedVideoId))
                .thenReturn(pipelineExecution(rootUnifiedVideoId, List.of()));

        List<AggregationResult> results =
                service.aggregateRuntimeWorkBatch(List.of(firstRawVideoId, secondRawVideoId));

        assertEquals(List.of("BOUND", "BOUND"), results.stream().map(AggregationResult::status).toList());
        verify(repository).mergeDuplicateUnifiedVideos(rootUnifiedVideoId, List.of(victimUnifiedVideoId));
        verify(repository, never()).createUnifiedVideo(firstRawVideo);
        verify(repository).bindRawToUnified(firstRawVideoId, rootUnifiedVideoId);
        verify(repository).bindRawToUnified(secondRawVideoId, rootUnifiedVideoId);
        verify(publisher).publishUnifiedIndex(rootUnifiedVideoId);
        verify(publisher).publishRawIndex(firstRawVideoId);
        verify(publisher).publishRawIndex(secondRawVideoId);
        verify(publisher).publishUnifiedDelete(victimUnifiedVideoId);
    }

    @Test
    void mixedGraphClusterReusesContextUnifiedWithoutCreatingNewUnified() {
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        UUID contextUnifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord firstRawVideo = readyRawVideo(firstRawVideoId, null);
        RawVideoAggregationRecord secondRawVideo = readyRawVideo(
                secondRawVideoId,
                "Codex Aggregation 1080p",
                "Codex Alias",
                null);
        UnifiedVideoAggregationCandidate context = unifiedCandidate(contextUnifiedVideoId, "Codex Aggregation", "2026");
        RawVideoAggregationCluster cluster = new RawVideoAggregationCluster(
                firstRawVideo,
                List.of(firstRawVideo, secondRawVideo),
                List.of(contextUnifiedVideoId));

        when(repository.findRawVideos(List.of(firstRawVideoId, secondRawVideoId)))
                .thenReturn(List.of(firstRawVideo, secondRawVideo));
        when(repository.findContextUnifiedCandidates(List.of(firstRawVideo, secondRawVideo))).thenReturn(List.of(context));
        when(rawVideoGraphClusterer.cluster(List.of(firstRawVideo, secondRawVideo), List.of(context))).thenReturn(List.of(cluster));
        when(repository.findExistingBinding(firstRawVideoId)).thenReturn(Optional.empty());
        when(repository.findExistingBinding(secondRawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(firstRawVideo)).thenReturn(AggregationMatchPlan.rootOnly(contextUnifiedVideoId));
        when(repository.findMatchPlan(secondRawVideo)).thenReturn(AggregationMatchPlan.rootOnly(contextUnifiedVideoId));
        when(aggregationPipelineRuntime.run(contextUnifiedVideoId))
                .thenReturn(pipelineExecution(contextUnifiedVideoId, List.of()));

        List<AggregationResult> results =
                service.aggregateRuntimeWorkBatch(List.of(firstRawVideoId, secondRawVideoId));

        assertEquals(List.of("BOUND", "BOUND"), results.stream().map(AggregationResult::status).toList());
        verify(repository, never()).createUnifiedVideo(firstRawVideo);
        verify(repository).bindRawToUnified(firstRawVideoId, contextUnifiedVideoId);
        verify(repository).bindRawToUnified(secondRawVideoId, contextUnifiedVideoId);
        verify(aggregationPipelineRuntime).run(contextUnifiedVideoId);
        verify(publisher).publishUnifiedIndex(contextUnifiedVideoId);
        verify(publisher).publishRawIndex(firstRawVideoId);
        verify(publisher).publishRawIndex(secondRawVideoId);
    }

    @Test
    void mixedGraphClusterMergesMultipleContextUnifiedVictims() {
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        UUID rootUnifiedVideoId = UUID.randomUUID();
        UUID victimUnifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord firstRawVideo = readyRawVideo(firstRawVideoId, null);
        RawVideoAggregationRecord secondRawVideo = readyRawVideo(
                secondRawVideoId,
                "Codex Aggregation 1080p",
                "Codex Alias",
                null);
        UnifiedVideoAggregationCandidate root = unifiedCandidate(rootUnifiedVideoId, "Codex Aggregation", "2026");
        UnifiedVideoAggregationCandidate victim = unifiedCandidate(victimUnifiedVideoId, "Codex Aggregation 1080p", "2026");
        RawVideoAggregationCluster cluster = new RawVideoAggregationCluster(
                firstRawVideo,
                List.of(firstRawVideo, secondRawVideo),
                List.of(rootUnifiedVideoId, victimUnifiedVideoId));

        when(repository.findRawVideos(List.of(firstRawVideoId, secondRawVideoId)))
                .thenReturn(List.of(firstRawVideo, secondRawVideo));
        when(repository.findContextUnifiedCandidates(List.of(firstRawVideo, secondRawVideo))).thenReturn(List.of(root, victim));
        when(rawVideoGraphClusterer.cluster(List.of(firstRawVideo, secondRawVideo), List.of(root, victim))).thenReturn(List.of(cluster));
        when(repository.findExistingBinding(firstRawVideoId)).thenReturn(Optional.empty());
        when(repository.findExistingBinding(secondRawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(firstRawVideo)).thenReturn(AggregationMatchPlan.rootOnly(rootUnifiedVideoId));
        when(repository.findMatchPlan(secondRawVideo)).thenReturn(AggregationMatchPlan.rootOnly(victimUnifiedVideoId));
        when(aggregationPipelineRuntime.run(rootUnifiedVideoId))
                .thenReturn(pipelineExecution(rootUnifiedVideoId, List.of()));

        List<AggregationResult> results =
                service.aggregateRuntimeWorkBatch(List.of(firstRawVideoId, secondRawVideoId));

        assertEquals(List.of("BOUND", "BOUND"), results.stream().map(AggregationResult::status).toList());
        verify(repository).mergeDuplicateUnifiedVideos(rootUnifiedVideoId, List.of(victimUnifiedVideoId));
        verify(repository, never()).createUnifiedVideo(firstRawVideo);
        verify(repository).bindRawToUnified(firstRawVideoId, rootUnifiedVideoId);
        verify(repository).bindRawToUnified(secondRawVideoId, rootUnifiedVideoId);
        verify(publisher).publishUnifiedIndex(rootUnifiedVideoId);
        verify(publisher).publishRawIndex(firstRawVideoId);
        verify(publisher).publishRawIndex(secondRawVideoId);
        verify(publisher).publishUnifiedDelete(victimUnifiedVideoId);
    }

    @Test
    void mergesDuplicateUnifiedVictimsBeforeBindingAndPublishesVictimDelete() {
        UUID rawVideoId = UUID.randomUUID();
        UUID rootUnifiedVideoId = UUID.randomUUID();
        UUID victimUnifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId);

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(repository.findExistingBinding(rawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(rawVideo)).thenReturn(AggregationMatchPlan.rootWithVictims(
                rootUnifiedVideoId,
                List.of(victimUnifiedVideoId)));
        UUID coverImageId = UUID.randomUUID();
        when(aggregationPipelineRuntime.run(rootUnifiedVideoId))
                .thenReturn(pipelineExecution(rootUnifiedVideoId, List.of(coverImageId)));

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("BOUND", result.status());
        InOrder ordered = inOrder(repository, aggregationPipelineRuntime, storageCommandPublisher, publisher);
        ordered.verify(repository).mergeDuplicateUnifiedVideos(rootUnifiedVideoId, List.of(victimUnifiedVideoId));
        ordered.verify(repository).upsertUnifiedFields(rootUnifiedVideoId, rawVideo);
        ordered.verify(repository).bindRawToUnified(rawVideoId, rootUnifiedVideoId);
        ordered.verify(aggregationPipelineRuntime).run(rootUnifiedVideoId);
        ordered.verify(storageCommandPublisher).enqueueCoverR2Sync(coverImageId);
        ordered.verify(repository).markBound(rawVideoId);
        ordered.verify(publisher).publishUnifiedIndex(rootUnifiedVideoId);
        ordered.verify(publisher).publishRawIndex(rawVideoId);
        ordered.verify(publisher).publishUnifiedDelete(victimUnifiedVideoId);
        verify(repository, never()).createUnifiedVideo(rawVideo);
    }

    @Test
    void skipsIneligibleRawVideoAndDoesNotPublishSearchSync() {
        UUID rawVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = new RawVideoAggregationRecord(
                rawVideoId,
                "Codex Aggregation",
                null,
                null,
                "2026",
                null,
                null,
                null,
                "电影",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PENDING",
                "SUCCESS",
                "PROCESSING");

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("NOT_ELIGIBLE", result.status());
        verify(repository).markPending(rawVideoId);
        verify(publisher, never()).publishUnifiedIndex(org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publishRawIndex(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pipelineFailureKeepsRawPendingDirtyUnifiedAndSuppressesSideEffects() {
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId);

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(repository.findExistingBinding(rawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(rawVideo)).thenReturn(AggregationMatchPlan.rootOnly(unifiedVideoId));
        when(aggregationPipelineRuntime.run(unifiedVideoId)).thenReturn(pipelineFailure(unifiedVideoId));

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("PIPELINE_FAILED", result.status());
        verify(repository).upsertUnifiedFields(unifiedVideoId, rawVideo);
        verify(repository).bindRawToUnified(rawVideoId, unifiedVideoId);
        verify(repository).markUnifiedDirty(unifiedVideoId);
        verify(repository).markPending(rawVideoId);
        verify(repository, never()).markBound(rawVideoId);
        verifyNoInteractions(storageCommandPublisher);
        verify(publisher, never()).publishUnifiedIndex(org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publishRawIndex(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void matchLockBusyReturnsRawToPendingWithoutCreatingUnifiedVideo() {
        UUID rawVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId);

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(matchKeyLock.tryAcquire(any())).thenReturn(Optional.empty());

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("MATCH_LOCK_BUSY", result.status());
        verify(repository).markPending(rawVideoId);
        verify(repository, never()).findMatchPlan(rawVideo);
        verify(repository, never()).createUnifiedVideo(rawVideo);
        verifyNoInteractions(storageCommandPublisher);
        verify(publisher, never()).publishUnifiedIndex(org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publishRawIndex(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clusterMatchLockBusyReturnsAllClusterMembersToPending() {
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        RawVideoAggregationRecord firstRawVideo = readyRawVideo(firstRawVideoId, null);
        RawVideoAggregationRecord secondRawVideo = readyRawVideo(
                secondRawVideoId,
                "Codex Aggregation 1080p",
                "Codex Alias",
                null);
        RawVideoAggregationCluster cluster =
                new RawVideoAggregationCluster(firstRawVideo, List.of(firstRawVideo, secondRawVideo));

        when(repository.findRawVideos(List.of(firstRawVideoId, secondRawVideoId)))
                .thenReturn(List.of(firstRawVideo, secondRawVideo));
        when(repository.findContextUnifiedCandidates(List.of(firstRawVideo, secondRawVideo))).thenReturn(List.of());
        when(rawVideoGraphClusterer.cluster(List.of(firstRawVideo, secondRawVideo), List.of())).thenReturn(List.of(cluster));
        when(matchKeyLock.tryAcquire(any())).thenReturn(Optional.empty());

        List<AggregationResult> results =
                service.aggregateRuntimeWorkBatch(List.of(firstRawVideoId, secondRawVideoId));

        assertEquals(
                List.of("MATCH_LOCK_BUSY", "MATCH_LOCK_BUSY"),
                results.stream().map(AggregationResult::status).toList());
        verify(repository).markPending(firstRawVideoId);
        verify(repository).markPending(secondRawVideoId);
        verify(repository, never()).createUnifiedVideo(firstRawVideo);
        verifyNoInteractions(storageCommandPublisher);
        verify(publisher, never()).publishUnifiedIndex(org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publishRawIndex(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recalculateKeepsUnifiedDirtyWhenAnyRawPipelineFails() {
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId);

        when(repository.findRawIdsForUnified(unifiedVideoId)).thenReturn(List.of(rawVideoId));
        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(repository.findExistingBinding(rawVideoId)).thenReturn(Optional.of(unifiedVideoId));
        when(aggregationPipelineRuntime.run(unifiedVideoId)).thenReturn(pipelineFailure(unifiedVideoId));

        int processed = service.recalculateUnified(unifiedVideoId);

        assertEquals(0, processed);
        verify(repository).markPending(rawVideoId);
        verify(repository).markUnifiedDirty(unifiedVideoId);
        verify(repository, never()).markUnifiedSynced(unifiedVideoId);
        verify(publisher, never()).publishUnifiedIndex(unifiedVideoId);
        verify(publisher, never()).publishRawIndex(rawVideoId);
    }

    @Test
    void recalculateDirtyUnifiedUsesCappedDirtySelection() {
        UUID firstUnifiedId = UUID.randomUUID();
        UUID secondUnifiedId = UUID.randomUUID();

        when(repository.findDirtyUnifiedIds(2)).thenReturn(List.of(firstUnifiedId, secondUnifiedId));
        when(repository.findRawIdsForUnified(firstUnifiedId)).thenReturn(List.of());
        when(repository.findRawIdsForUnified(secondUnifiedId)).thenReturn(List.of());

        var result = service.recalculateDirtyUnified(2);

        assertEquals("unified-recalculate", result.taskName());
        assertEquals(2, result.candidates());
        assertEquals(0, result.processed());
        assertEquals(List.of(firstUnifiedId, secondUnifiedId), result.entityIds());
        verify(repository).findDirtyUnifiedIds(2);
        verify(repository).markUnifiedSynced(firstUnifiedId);
        verify(repository).markUnifiedSynced(secondUnifiedId);
        verify(publisher).publishUnifiedIndex(firstUnifiedId);
        verify(publisher).publishUnifiedIndex(secondUnifiedId);
    }

    @Test
    void prepareAggregationResetDeletesOwnedRowsAndReturnsCounts() {
        UUID rawVideoId = UUID.randomUUID();
        when(repository.sampleRawVideoIds(3)).thenReturn(List.of(rawVideoId));
        when(repository.countRawVideos()).thenReturn(11L);
        when(repository.countUnifiedVideos()).thenReturn(4L);
        when(repository.countRawUnifiedBindings()).thenReturn(7L);
        when(repository.deleteAllAggregationResetOwnedRows()).thenReturn(19);

        var result = service.prepareAggregationReset(3);

        assertEquals("aggregation-reset", result.taskName());
        assertEquals("prepare", result.stepName());
        assertEquals(11L, result.rawVideoCount());
        assertEquals(4L, result.unifiedVideoCount());
        assertEquals(7L, result.bindingCount());
        assertEquals(19L, result.changedRows());
        assertEquals(List.of(rawVideoId), result.sampleRawVideoIds());
        verify(repository).deleteAllAggregationResetOwnedRows();
    }

    @Test
    void markAllRawAggregationPendingReturnsRawCountAndChangedRows() {
        UUID rawVideoId = UUID.randomUUID();
        when(repository.sampleRawVideoIds(2)).thenReturn(List.of(rawVideoId));
        when(repository.countRawVideos()).thenReturn(8L);
        when(repository.markAllRawAggregationPending()).thenReturn(8);

        var result = service.markAllRawAggregationPending(2);

        assertEquals("aggregation-reset", result.taskName());
        assertEquals("mark-raw-pending", result.stepName());
        assertEquals(8L, result.rawVideoCount());
        assertEquals(0L, result.unifiedVideoCount());
        assertEquals(0L, result.bindingCount());
        assertEquals(8L, result.changedRows());
        assertEquals(List.of(rawVideoId), result.sampleRawVideoIds());
        verify(repository).markAllRawAggregationPending();
    }

    @Test
    void enqueuePendingRawWorkPublishesRuntimeWorkForPendingRawVideos() {
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        when(repository.findPendingRawIdsWithoutRuntimeProgress(50))
                .thenReturn(List.of(firstRawVideoId, secondRawVideoId));

        var result = service.enqueuePendingRawWork(50);

        assertEquals("aggregation-pending-backfill", result.taskName());
        assertEquals(2, result.candidates());
        assertEquals(2, result.processed());
        assertEquals(List.of(firstRawVideoId, secondRawVideoId), result.entityIds());
        verify(workPublisher).enqueue(firstRawVideoId, null, "aggregation-pending-backfill");
        verify(workPublisher).enqueue(secondRawVideoId, null, "aggregation-pending-backfill");
    }

    @Test
    void backfillUnifiedCoversUpdatesCoverAndPublishesFollowUpSyncs() {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID coverImageId = UUID.randomUUID();
        when(repository.backfillUnifiedCoverImagesFromRaw(20))
                .thenReturn(List.of(new UnifiedCoverBackfillResult(unifiedVideoId, coverImageId, "LOCAL", "LOCAL_STORED")));

        var result = service.backfillUnifiedCovers(20);

        assertEquals("aggregation-cover-backfill", result.taskName());
        assertEquals(1, result.candidates());
        assertEquals(1, result.processed());
        assertEquals(List.of(unifiedVideoId), result.entityIds());
        verify(storageCommandPublisher).enqueueCoverR2Sync(coverImageId);
        verify(publisher).publishUnifiedIndex(unifiedVideoId);
    }

    @Test
    void backfillUnifiedCoversSkipsR2SyncForExternalCover() {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID coverImageId = UUID.randomUUID();
        when(repository.backfillUnifiedCoverImagesFromRaw(20))
                .thenReturn(List.of(new UnifiedCoverBackfillResult(unifiedVideoId, coverImageId, "EXTERNAL", "UNPROCESSED")));

        var result = service.backfillUnifiedCovers(20);

        assertEquals(1, result.processed());
        verify(storageCommandPublisher, never()).enqueueCoverR2Sync(coverImageId);
        verify(publisher).publishUnifiedIndex(unifiedVideoId);
    }

    @Test
    void backfillUnifiedAdultAssessmentsRebuildsAssessmentAndPublishesSearchSync() {
        UUID firstUnifiedVideoId = UUID.randomUUID();
        UUID secondUnifiedVideoId = UUID.randomUUID();
        when(repository.findAdultAssessmentBackfillIds(20))
                .thenReturn(List.of(firstUnifiedVideoId, secondUnifiedVideoId));
        when(repository.rebuildUnifiedAdultAssessments(List.of(firstUnifiedVideoId, secondUnifiedVideoId)))
                .thenReturn(List.of(firstUnifiedVideoId, secondUnifiedVideoId));

        var result = service.backfillUnifiedAdultAssessments(20);

        assertEquals("aggregation-adult-assessment-backfill", result.taskName());
        assertEquals(2, result.candidates());
        assertEquals(2, result.processed());
        assertEquals(List.of(firstUnifiedVideoId, secondUnifiedVideoId), result.entityIds());
        verify(repository).rebuildUnifiedAdultAssessments(List.of(firstUnifiedVideoId, secondUnifiedVideoId));
        verify(publisher).publishUnifiedIndex(firstUnifiedVideoId);
        verify(publisher).publishUnifiedIndex(secondUnifiedVideoId);
    }

    @Test
    void backfillUnifiedAdultAssessmentsCanSkipSearchSyncWhenFullReindexWillFollow() {
        UUID unifiedVideoId = UUID.randomUUID();
        when(repository.findAdultAssessmentBackfillIds(20)).thenReturn(List.of(unifiedVideoId));
        when(repository.rebuildUnifiedAdultAssessments(List.of(unifiedVideoId))).thenReturn(List.of(unifiedVideoId));

        var result = service.backfillUnifiedAdultAssessments(20, false);

        assertEquals("aggregation-adult-assessment-backfill", result.taskName());
        assertEquals(1, result.candidates());
        assertEquals(1, result.processed());
        assertEquals(List.of(unifiedVideoId), result.entityIds());
        verify(repository).rebuildUnifiedAdultAssessments(List.of(unifiedVideoId));
        verify(publisher, never()).publishUnifiedIndex(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void manuallyMergesUnifiedVideosAndPublishesSearchSync() {
        UUID rootUnifiedVideoId = UUID.randomUUID();
        UUID victimUnifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID coverImageId = UUID.randomUUID();
        when(aggregationPipelineRuntime.run(rootUnifiedVideoId))
                .thenReturn(pipelineExecution(rootUnifiedVideoId, List.of(coverImageId)));
        when(repository.findRawIdsForUnified(rootUnifiedVideoId)).thenReturn(List.of(rawVideoId));

        ManualUnifiedMergeResponse result =
                service.mergeUnifiedVideos(List.of(rootUnifiedVideoId, victimUnifiedVideoId));

        assertEquals("MERGED", result.status());
        assertEquals(rootUnifiedVideoId, result.rootUnifiedVideoId());
        assertEquals(List.of(victimUnifiedVideoId), result.victimUnifiedVideoIds());
        assertEquals(List.of(rawVideoId), result.rawVideoIds());
        InOrder ordered = inOrder(repository, aggregationPipelineRuntime, publisher, storageCommandPublisher);
        ordered.verify(repository).mergeDuplicateUnifiedVideos(rootUnifiedVideoId, List.of(victimUnifiedVideoId));
        ordered.verify(aggregationPipelineRuntime).run(rootUnifiedVideoId);
        ordered.verify(repository).findRawIdsForUnified(rootUnifiedVideoId);
        ordered.verify(repository).markUnifiedSynced(rootUnifiedVideoId);
        ordered.verify(publisher).publishUnifiedIndex(rootUnifiedVideoId);
        ordered.verify(publisher).publishRawIndex(rawVideoId);
        ordered.verify(publisher).publishUnifiedDelete(victimUnifiedVideoId);
        ordered.verify(storageCommandPublisher).enqueueCoverR2Sync(coverImageId);
    }

    @Test
    void manualMergeMarksRootDirtyWhenPipelineFails() {
        UUID rootUnifiedVideoId = UUID.randomUUID();
        UUID victimUnifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        when(aggregationPipelineRuntime.run(rootUnifiedVideoId)).thenReturn(pipelineFailure(rootUnifiedVideoId));
        when(repository.findRawIdsForUnified(rootUnifiedVideoId)).thenReturn(List.of(rawVideoId));

        ManualUnifiedMergeResponse result =
                service.mergeUnifiedVideos(List.of(rootUnifiedVideoId, victimUnifiedVideoId));

        assertEquals("PIPELINE_FAILED", result.status());
        verify(repository).markUnifiedDirty(rootUnifiedVideoId);
        verify(repository, never()).markUnifiedSynced(rootUnifiedVideoId);
        verify(publisher).publishUnifiedDelete(victimUnifiedVideoId);
        verify(publisher, never()).publishUnifiedIndex(rootUnifiedVideoId);
        verify(publisher, never()).publishRawIndex(rawVideoId);
    }

    @Test
    void reusesExistingUnifiedVideoWhenSimilarTitleMatchesWithoutExternalIds() {
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = readyRawVideo(rawVideoId, null);

        when(repository.findRawVideo(rawVideoId)).thenReturn(Optional.of(rawVideo));
        when(repository.findExistingBinding(rawVideoId)).thenReturn(Optional.empty());
        when(repository.findMatchPlan(rawVideo)).thenReturn(AggregationMatchPlan.rootOnly(unifiedVideoId));
        when(aggregationPipelineRuntime.run(unifiedVideoId))
                .thenReturn(pipelineExecution(unifiedVideoId, List.of()));

        AggregationResult result = service.aggregateRuntimeWork(rawVideoId);

        assertEquals("BOUND", result.status());
        verify(repository, never()).createUnifiedVideo(rawVideo);
        verify(repository).upsertUnifiedFields(unifiedVideoId, rawVideo);
        verify(repository).bindRawToUnified(rawVideoId, unifiedVideoId);
        verify(aggregationPipelineRuntime).run(unifiedVideoId);
        verify(repository).markBound(rawVideoId);
        verify(publisher).publishUnifiedIndex(unifiedVideoId);
        verify(publisher).publishRawIndex(rawVideoId);
    }

    private RawVideoAggregationRecord readyRawVideo(UUID rawVideoId) {
        return readyRawVideo(rawVideoId, "1234567");
    }

    private AggregationPipelineExecution pipelineExecution(UUID unifiedVideoId, List<UUID> coverR2SyncImageIds) {
        return new AggregationPipelineExecution(unifiedVideoId, coverR2SyncImageIds, List.of());
    }

    private AggregationPipelineExecution pipelineFailure(UUID unifiedVideoId) {
        return new AggregationPipelineExecution(
                unifiedVideoId,
                List.of(UUID.randomUUID()),
                List.of(new AggregationPipelineStageFailure(
                        AggregationPipelineStage.METADATA,
                        "MetadataHandler",
                        30,
                        "metadata failed")));
    }

    private RawVideoAggregationRecord readyRawVideo(UUID rawVideoId, String doubanId) {
        return readyRawVideo(rawVideoId, "Codex Aggregation", "Codex Alias", doubanId);
    }

    private RawVideoAggregationRecord readyRawVideo(
            UUID rawVideoId,
            String title,
            String aliasTitle,
            String doubanId) {
        return new RawVideoAggregationRecord(
                rawVideoId,
                title,
                aliasTitle,
                "Aggregation description",
                "2026",
                new BigDecimal("8.9"),
                LocalDate.of(2026, 6, 4),
                "12",
                "45m",
                "Ready",
                null,
                null,
                "电影",
                doubanId,
                null,
                null,
                null,
                "READY",
                "SUCCESS",
                "PROCESSING");
    }

    private UnifiedVideoAggregationCandidate unifiedCandidate(UUID id, String title, String year) {
        return new UnifiedVideoAggregationCandidate(
                id,
                title,
                null,
                null,
                year,
                "12",
                "45m",
                null,
                "电影",
                null,
                null,
                null,
                null,
                null);
    }
}
