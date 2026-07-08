package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggregationMatchingStrategyTest {

    private final AggregationMatchingStrategy strategy = new AggregationMatchingStrategy();

    @Test
    void matchesTitleYearCandidateWhenExternalIdsAreAbsent() {
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Aggregation S1 1080p",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate candidate = candidate(
                unifiedVideoId,
                "Codex Aggregation",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals(
                unifiedVideoId,
                strategy.findBestMatch(rawVideo, List.of(candidate)).orElseThrow());
    }

    @Test
    void externalIdConflictBlocksOtherwiseSimilarCandidate() {
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Aggregation",
                null,
                "2026",
                "111111",
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate candidate = candidate(
                UUID.randomUUID(),
                "Codex Aggregation",
                null,
                "2026",
                "222222",
                null,
                null,
                null,
                null,
                null);

        assertTrue(strategy.findBestMatch(rawVideo, List.of(candidate)).isEmpty());
    }

    @Test
    void seasonSensitiveCandidateDoesNotMergeIncorrectly() {
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Chronicles S2",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate candidate = candidate(
                UUID.randomUUID(),
                "Codex Chronicles S1",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);

        assertTrue(strategy.findBestMatch(rawVideo, List.of(candidate)).isEmpty());
    }

    @Test
    void versionSensitiveCandidateDoesNotMergeIncorrectly() {
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Legend 真人版",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                "真人版");
        UnifiedVideoAggregationCandidate candidate = candidate(
                UUID.randomUUID(),
                "Codex Legend 动画版",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                "动画版");

        assertTrue(strategy.findBestMatch(rawVideo, List.of(candidate)).isEmpty());
    }

    @Test
    void similarTitleBindsToExistingUnifiedCandidate() {
        UUID unifiedVideoId = UUID.randomUUID();
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Signal",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate candidate = candidate(
                unifiedVideoId,
                "Codex Signal: The Movie",
                "Codex Signal",
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals(
                unifiedVideoId,
                strategy.findBestMatch(rawVideo, List.of(candidate)).orElseThrow());
    }

    @Test
    void matchPlanUsesBestCandidateAsRootAndTreatsOtherMatchesAsVictims() {
        UUID rootId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID victimId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Signal",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate root = candidate(
                rootId,
                "Codex Signal",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate victim = candidate(
                victimId,
                "Codex Signal: The Movie",
                "Codex Signal",
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);

        AggregationMatchPlan plan = strategy.findMatchPlan(rawVideo, List.of(victim, root));

        assertEquals(rootId, plan.rootUnifiedVideoId());
        assertEquals(List.of(victimId), plan.victimUnifiedVideoIds());
    }

    @Test
    void exactTitleFallsBackToTitleConfidenceWhenMetadataIsEmptyLikeV1() {
        RawVideoAggregationRecord rawVideo = rawVideo(
                "Codex Signal",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);
        UnifiedVideoAggregationCandidate candidate = candidate(
                UUID.randomUUID(),
                "Codex Signal",
                null,
                "2026",
                null,
                null,
                null,
                null,
                null,
                null);

        assertEquals(0.95, strategy.calculateSimilarity(rawVideo, candidate), 0.0001);
    }

    @Test
    void metadataMismatchUsesV1WeightedFormulaAndCanBlockGraphEdges() {
        RawVideoAggregationRecord rawVideo = rawVideoWithMetadata(
                "Codex Signal",
                "2026",
                "12",
                Set.of("Actor A"),
                Set.of("Director A"),
                Set.of());
        UnifiedVideoAggregationCandidate candidate = candidateWithMetadata(
                UUID.randomUUID(),
                "Codex Signal",
                "2026",
                "12",
                Set.of("Actor B"),
                Set.of("Director B"),
                Set.of());

        double score = strategy.calculateSimilarity(rawVideo, candidate);

        assertEquals(0.70, score, 0.0001);
        assertTrue(score < 0.85);
        assertTrue(strategy.findBestMatch(rawVideo, List.of(candidate)).isEmpty());
    }

    @Test
    void sharedActorDirectorMetadataRaisesScoreLikeV1Jaccard() {
        RawVideoAggregationRecord rawVideo = rawVideoWithMetadata(
                "Codex Signal",
                "2026",
                "12",
                Set.of("Actor A", "Actor B"),
                Set.of("Director A"),
                Set.of());
        UnifiedVideoAggregationCandidate candidate = candidateWithMetadata(
                UUID.randomUUID(),
                "Codex Signal",
                "2026",
                "12",
                Set.of("Actor A"),
                Set.of("Director A"),
                Set.of());

        double score = strategy.calculateSimilarity(rawVideo, candidate);

        assertEquals(0.90, score, 0.0001);
        assertTrue(score >= 0.85);
    }

    @Test
    void episodePenaltyCanDropTitleOnlyScoreBelowGraphEdgeThreshold() {
        RawVideoAggregationRecord rawVideo = rawVideoWithMetadata(
                "Codex Signal",
                "2026",
                "1",
                Set.of(),
                Set.of(),
                Set.of());
        UnifiedVideoAggregationCandidate candidate = candidateWithMetadata(
                UUID.randomUUID(),
                "Codex Signal",
                "2026",
                "12",
                Set.of(),
                Set.of(),
                Set.of());

        double score = strategy.calculateSimilarity(rawVideo, candidate);

        assertEquals(0.80, score, 0.0001);
        assertTrue(score < 0.85);
    }

    @Test
    void areaPenaltyAppliesOnlyWhenBothSidesHaveNonMatchingAreas() {
        RawVideoAggregationRecord rawVideo = rawVideoWithMetadata(
                "Codex Signal",
                "2026",
                "12",
                Set.of(),
                Set.of(),
                Set.of("日本"));
        UnifiedVideoAggregationCandidate differentArea = candidateWithMetadata(
                UUID.randomUUID(),
                "Codex Signal",
                "2026",
                "12",
                Set.of(),
                Set.of(),
                Set.of("美国"));
        UnifiedVideoAggregationCandidate compatibleArea = candidateWithMetadata(
                UUID.randomUUID(),
                "Codex Signal",
                "2026",
                "12",
                Set.of(),
                Set.of(),
                Set.of("日本地区"));

        assertEquals(0.85, strategy.calculateSimilarity(rawVideo, differentArea), 0.0001);
        assertEquals(0.95, strategy.calculateSimilarity(rawVideo, compatibleArea), 0.0001);
    }

    private RawVideoAggregationRecord rawVideo(
            String title,
            String aliasTitle,
            String year,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            Integer season,
            String remarks) {
        return new RawVideoAggregationRecord(
                UUID.randomUUID(),
                title,
                aliasTitle,
                "Aggregation description",
                year,
                new BigDecimal("8.9"),
                LocalDate.of(2026, 6, 4),
                "12",
                "45m",
                remarks,
                null,
                season,
                "电影",
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                "READY",
                "SUCCESS",
                "PROCESSING");
    }

    private UnifiedVideoAggregationCandidate candidate(
            UUID id,
            String title,
            String aliasTitle,
            String year,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            Integer season,
            String remarks) {
        return new UnifiedVideoAggregationCandidate(
                id,
                title,
                aliasTitle,
                null,
                year,
                "12",
                "45m",
                remarks,
                "电影",
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                season);
    }

    private RawVideoAggregationRecord rawVideoWithMetadata(
            String title,
            String year,
            String totalEpisodes,
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaNames) {
        return new RawVideoAggregationRecord(
                UUID.randomUUID(),
                title,
                null,
                "Aggregation description",
                year,
                new BigDecimal("8.9"),
                LocalDate.of(2026, 6, 4),
                totalEpisodes,
                "45m",
                null,
                null,
                null,
                "电影",
                actorNames,
                directorNames,
                areaNames,
                null,
                null,
                null,
                null,
                "READY",
                "SUCCESS",
                "PROCESSING");
    }

    private UnifiedVideoAggregationCandidate candidateWithMetadata(
            UUID id,
            String title,
            String year,
            String totalEpisodes,
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaNames) {
        return new UnifiedVideoAggregationCandidate(
                id,
                title,
                null,
                null,
                year,
                totalEpisodes,
                "45m",
                null,
                "电影",
                actorNames,
                directorNames,
                areaNames,
                null,
                null,
                null,
                null,
                null);
    }
}
