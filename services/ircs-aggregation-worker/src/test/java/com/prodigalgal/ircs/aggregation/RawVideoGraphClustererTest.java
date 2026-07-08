package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawVideoGraphClustererTest {

    private final RawVideoGraphClusterer clusterer = new RawVideoGraphClusterer(new AggregationMatchingStrategy());

    @Test
    void clustersSameBatchRawVideosThroughJgraphtConnectedComponents() {
        RawVideoAggregationRecord first = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord second = rawVideo(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal 1080p",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord third = rawVideo(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Codex Signal: The Movie",
                "Codex Signal",
                "2026",
                "电影",
                null,
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(first, second, third));

        assertEquals(1, clusters.size());
        assertEquals(List.of(third.id(), first.id(), second.id()), clusters.getFirst().rawVideoIds());
    }

    @Test
    void usesV1YearBucketsForRawEdges() {
        RawVideoAggregationRecord year2025 = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2025",
                "电影",
                null,
                null);
        RawVideoAggregationRecord year2026 = rawVideo(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal 1080p",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord unknownYear = rawVideo(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Codex Signal",
                null,
                null,
                "电影",
                null,
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(year2025, year2026, unknownYear));

        assertEquals(2, clusters.size());
        assertEquals(List.of(year2026.id(), year2025.id()), clusters.get(0).rawVideoIds());
        assertEquals(List.of(unknownYear.id()), clusters.get(1).rawVideoIds());
    }

    @Test
    void keepsSeasonVersionAndExternalIdConflictsOutOfRawClusters() {
        RawVideoAggregationRecord seasonOne = rawVideo(
                UUID.randomUUID(),
                "Codex Chronicles S1",
                null,
                "2026",
                "电视剧",
                "111",
                null);
        RawVideoAggregationRecord seasonTwo = rawVideo(
                UUID.randomUUID(),
                "Codex Chronicles S2",
                null,
                "2026",
                "电视剧",
                "111",
                null);
        RawVideoAggregationRecord realVersion = rawVideo(
                UUID.randomUUID(),
                "Codex Legend 真人版",
                null,
                "2026",
                "电影",
                null,
                "真人版");
        RawVideoAggregationRecord animeVersion = rawVideo(
                UUID.randomUUID(),
                "Codex Legend 动画版",
                null,
                "2026",
                "电影",
                null,
                "动画版");
        RawVideoAggregationRecord idConflict = rawVideo(
                UUID.randomUUID(),
                "Codex Signal",
                null,
                "2026",
                "电影",
                "222",
                null);
        RawVideoAggregationRecord sameTitleDifferentId = rawVideo(
                UUID.randomUUID(),
                "Codex Signal",
                null,
                "2026",
                "电影",
                "333",
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(
                seasonOne,
                seasonTwo,
                realVersion,
                animeVersion,
                idConflict,
                sameTitleDifferentId));

        assertEquals(6, clusters.size());
        assertEquals(6, clusters.stream().filter(cluster -> !cluster.isMultiRawCluster()).count());
    }

    @Test
    void mixedGraphUsesUnifiedContextAsBridgeAndSkipsPureUnifiedComponents() {
        RawVideoAggregationRecord first = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord second = rawVideo(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal 1080p",
                null,
                "2026",
                "电影",
                null,
                null);
        UnifiedVideoAggregationCandidate context = unified(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                null);
        UnifiedVideoAggregationCandidate unrelatedPureContext = unified(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "Another Signal",
                null,
                "2026",
                "电影",
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(
                List.of(first, second),
                List.of(context, unrelatedPureContext));

        assertEquals(1, clusters.size());
        assertEquals(List.of(first.id(), second.id()), clusters.getFirst().rawVideoIds());
        assertEquals(List.of(context.id()), clusters.getFirst().contextUnifiedVideoIds());
    }

    @Test
    void mixedGraphDoesNotUseConflictingUnifiedContextAsBridge() {
        RawVideoAggregationRecord raw = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                "111",
                null);
        UnifiedVideoAggregationCandidate conflict = unified(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                "222");

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(raw), List.of(conflict));

        assertEquals(1, clusters.size());
        assertEquals(List.of(raw.id()), clusters.getFirst().rawVideoIds());
        assertEquals(List.of(), clusters.getFirst().contextUnifiedVideoIds());
    }

    @Test
    void starPeelingSplitsMembersBelowLeaderConsistencyThreshold() {
        RawVideoAggregationRecord leader = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord strong = rawVideo(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal 1080p",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord weakButConnected = rawVideo(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Codex Signal The Remote Cut",
                null,
                "2026",
                "电影",
                null,
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(leader, strong, weakButConnected));

        assertEquals(2, clusters.size());
        assertEquals(List.of(strong.id(), leader.id()), clusters.get(0).rawVideoIds());
        assertEquals(List.of(weakButConnected.id()), clusters.get(1).rawVideoIds());
    }

    @Test
    void categoryBucketPreventsCrossCategoryRawEdgesLikeV1() {
        RawVideoAggregationRecord movie = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                null,
                null);
        RawVideoAggregationRecord series = rawVideo(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal 1080p",
                null,
                "2026",
                "电视剧",
                null,
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(movie, series));

        assertEquals(2, clusters.size());
        assertEquals(List.of(movie.id()), clusters.get(0).rawVideoIds());
        assertEquals(List.of(series.id()), clusters.get(1).rawVideoIds());
    }

    @Test
    void categoryBucketPreventsCrossCategoryUnifiedContextBridge() {
        RawVideoAggregationRecord raw = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                "电影",
                null,
                null);
        UnifiedVideoAggregationCandidate context = unified(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Codex Signal",
                null,
                "2026",
                "电视剧",
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(raw), List.of(context));

        assertEquals(1, clusters.size());
        assertEquals(List.of(raw.id()), clusters.getFirst().rawVideoIds());
        assertEquals(List.of(), clusters.getFirst().contextUnifiedVideoIds());
    }

    @Test
    void unknownCategoryOnlyComparesWithUnknownCategoryLikeV1() {
        RawVideoAggregationRecord unknown = rawVideo(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                null,
                "2026",
                null,
                null,
                null);
        RawVideoAggregationRecord concrete = rawVideo(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal 1080p",
                null,
                "2026",
                "电影",
                null,
                null);
        UnifiedVideoAggregationCandidate unknownContext = unified(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Codex Signal",
                null,
                "2026",
                null,
                null);

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(
                List.of(unknown, concrete),
                List.of(unknownContext));

        assertEquals(2, clusters.size());
        assertEquals(List.of(unknown.id()), clusters.get(0).rawVideoIds());
        assertEquals(List.of(unknownContext.id()), clusters.get(0).contextUnifiedVideoIds());
        assertEquals(List.of(concrete.id()), clusters.get(1).rawVideoIds());
    }

    @Test
    void actorDirectorMismatchPreventsSameTitleRawGraphEdgeLikeV1Scoring() {
        RawVideoAggregationRecord first = rawVideoWithMetadata(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                "2026",
                "电影",
                "12",
                Set.of("Actor A"),
                Set.of("Director A"),
                Set.of());
        RawVideoAggregationRecord second = rawVideoWithMetadata(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal",
                "2026",
                "电影",
                "12",
                Set.of("Actor B"),
                Set.of("Director B"),
                Set.of());

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(first, second));

        assertEquals(2, clusters.size());
        assertEquals(List.of(first.id()), clusters.get(0).rawVideoIds());
        assertEquals(List.of(second.id()), clusters.get(1).rawVideoIds());
    }

    @Test
    void rawUnifiedContextBridgeRequiresV1MetadataScore() {
        RawVideoAggregationRecord raw = rawVideoWithMetadata(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                "2026",
                "电影",
                "12",
                Set.of("Actor A"),
                Set.of("Director A"),
                Set.of());
        UnifiedVideoAggregationCandidate context = unifiedWithMetadata(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Codex Signal",
                "2026",
                "电影",
                "12",
                Set.of("Actor B"),
                Set.of("Director B"),
                Set.of());

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(raw), List.of(context));

        assertEquals(1, clusters.size());
        assertEquals(List.of(raw.id()), clusters.getFirst().rawVideoIds());
        assertEquals(List.of(), clusters.getFirst().contextUnifiedVideoIds());
    }

    @Test
    void episodePenaltyPreventsSameTitleRawGraphEdgeLikeV1Scoring() {
        RawVideoAggregationRecord oneEpisode = rawVideoWithMetadata(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Codex Signal",
                "2026",
                "电影",
                "1",
                Set.of(),
                Set.of(),
                Set.of());
        RawVideoAggregationRecord twelveEpisodes = rawVideoWithMetadata(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Codex Signal",
                "2026",
                "电影",
                "12",
                Set.of(),
                Set.of(),
                Set.of());

        List<RawVideoAggregationCluster> clusters = clusterer.cluster(List.of(oneEpisode, twelveEpisodes));

        assertEquals(2, clusters.size());
        assertEquals(List.of(oneEpisode.id()), clusters.get(0).rawVideoIds());
        assertEquals(List.of(twelveEpisodes.id()), clusters.get(1).rawVideoIds());
    }

    private RawVideoAggregationRecord rawVideo(
            UUID id,
            String title,
            String aliasTitle,
            String year,
            String categoryName,
            String doubanId,
            String remarks) {
        return new RawVideoAggregationRecord(
                id,
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
                null,
                categoryName,
                doubanId,
                null,
                null,
                null,
                "READY",
                "SUCCESS",
                "PROCESSING");
    }

    private UnifiedVideoAggregationCandidate unified(
            UUID id,
            String title,
            String aliasTitle,
            String year,
            String categoryName,
            String doubanId) {
        return new UnifiedVideoAggregationCandidate(
                id,
                title,
                aliasTitle,
                null,
                year,
                "12",
                "45m",
                null,
                categoryName,
                doubanId,
                null,
                null,
                null,
                null);
    }

    private RawVideoAggregationRecord rawVideoWithMetadata(
            UUID id,
            String title,
            String year,
            String categoryName,
            String totalEpisodes,
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaNames) {
        return new RawVideoAggregationRecord(
                id,
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
                categoryName,
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

    private UnifiedVideoAggregationCandidate unifiedWithMetadata(
            UUID id,
            String title,
            String year,
            String categoryName,
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
                categoryName,
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
