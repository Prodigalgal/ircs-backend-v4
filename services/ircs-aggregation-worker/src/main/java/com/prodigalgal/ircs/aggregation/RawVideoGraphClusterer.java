package com.prodigalgal.ircs.aggregation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RawVideoGraphClusterer {

    static final double EDGE_THRESHOLD = 0.85;
    static final double CONSISTENCY_THRESHOLD = 0.90;

    private final AggregationMatchingStrategy matchingStrategy;

    public List<RawVideoAggregationCluster> cluster(List<RawVideoAggregationRecord> rawVideos) {
        return cluster(rawVideos, List.of());
    }

    public List<RawVideoAggregationCluster> cluster(
            List<RawVideoAggregationRecord> rawVideos,
            List<UnifiedVideoAggregationCandidate> contextUnifiedVideos) {
        List<RawVideoAggregationRecord> candidates = rawVideos == null
                ? List.of()
                : rawVideos.stream()
                        .filter(java.util.Objects::nonNull)
                        .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UnifiedVideoAggregationCandidate> contextCandidates = contextUnifiedVideos == null
                ? List.of()
                : contextUnifiedVideos.stream()
                        .filter(java.util.Objects::nonNull)
                        .toList();
        if (candidates.size() == 1 && contextCandidates.isEmpty()) {
            RawVideoAggregationRecord only = candidates.getFirst();
            return List.of(new RawVideoAggregationCluster(only, List.of(only)));
        }

        Map<UUID, Integer> inputOrder = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            inputOrder.put(candidates.get(i).id(), i);
        }

        Graph<AggregationGraphCandidate, DefaultWeightedEdge> graph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        List<AggregationGraphCandidate> nodes = new ArrayList<>();
        candidates.stream()
                .map(AggregationGraphCandidate::raw)
                .forEach(nodes::add);
        contextCandidates.stream()
                .map(AggregationGraphCandidate::unified)
                .forEach(nodes::add);
        nodes.forEach(graph::addVertex);
        connectSimilarCandidates(graph, nodes);

        ConnectivityInspector<AggregationGraphCandidate, DefaultWeightedEdge> inspector =
                new ConnectivityInspector<>(graph);
        return inspector.connectedSets().stream()
                .flatMap(component -> decomposeToStars(component, inputOrder).stream())
                .sorted(Comparator.comparingInt(cluster -> cluster.members().stream()
                        .map(RawVideoAggregationRecord::id)
                        .map(inputOrder::get)
                        .min(Integer::compareTo)
                        .orElse(Integer.MAX_VALUE)))
                .toList();
    }

    private void connectSimilarCandidates(
            Graph<AggregationGraphCandidate, DefaultWeightedEdge> graph,
            List<AggregationGraphCandidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                AggregationGraphCandidate left = candidates.get(i);
                AggregationGraphCandidate right = candidates.get(j);
                if (left instanceof AggregationGraphCandidate.UnifiedNode
                        && right instanceof AggregationGraphCandidate.UnifiedNode) {
                    continue;
                }
                if (!categoryBucket(left).equals(categoryBucket(right))) {
                    continue;
                }
                if (!isYearBucketCompatible(left.year(), right.year())) {
                    continue;
                }
                double score = matchingStrategy.calculateSimilarity(left, right);
                if (score >= EDGE_THRESHOLD) {
                    DefaultWeightedEdge edge = graph.addEdge(left, right);
                    if (edge != null) {
                        graph.setEdgeWeight(edge, score);
                    }
                }
            }
        }
    }

    private List<RawVideoAggregationCluster> decomposeToStars(
            Set<AggregationGraphCandidate> component,
            Map<UUID, Integer> inputOrder) {
        if (component.stream().noneMatch(AggregationGraphCandidate.RawNode.class::isInstance)) {
            return List.of();
        }
        List<RawVideoAggregationCluster> clusters = new ArrayList<>();
        Set<AggregationGraphCandidate> remaining = new LinkedHashSet<>(sortByInputOrder(component, inputOrder));
        int safeguard = 0;
        int maxIterations = component.size() + 1;

        while (!remaining.isEmpty() && safeguard++ < maxIterations) {
            if (remaining.size() == 1) {
                addClusterIfRaw(clusters, remaining);
                break;
            }

            AggregationGraphCandidate leader = electLeader(remaining, inputOrder);
            List<AggregationGraphCandidate> members = new ArrayList<>();
            members.add(leader);
            remaining.remove(leader);

            for (AggregationGraphCandidate candidate : sortByInputOrder(new HashSet<>(remaining), inputOrder)) {
                double score = matchingStrategy.calculateSimilarity(leader, candidate);
                if (score >= CONSISTENCY_THRESHOLD) {
                    members.add(candidate);
                    remaining.remove(candidate);
                }
            }
            addClusterIfRaw(clusters, members);
        }
        return clusters;
    }

    private AggregationGraphCandidate electLeader(
            Set<AggregationGraphCandidate> candidates,
            Map<UUID, Integer> inputOrder) {
        return candidates.stream()
                .filter(AggregationGraphCandidate.UnifiedNode.class::isInstance)
                .findFirst()
                .orElseGet(() -> candidates.stream()
                .max(Comparator
                        .comparingInt((AggregationGraphCandidate candidate) -> hasAnyExternalId(candidate) ? 1 : 0)
                        .thenComparingInt(this::titleLength)
                        .thenComparingInt(candidate -> -inputOrder.getOrDefault(candidate.id(), Integer.MAX_VALUE)))
                .orElseGet(() -> candidates.iterator().next()));
    }

    private List<AggregationGraphCandidate> sortByInputOrder(
            Set<AggregationGraphCandidate> candidates,
            Map<UUID, Integer> inputOrder) {
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((AggregationGraphCandidate candidate) -> candidate instanceof AggregationGraphCandidate.UnifiedNode ? 1 : 0)
                        .thenComparingInt(candidate -> inputOrder.getOrDefault(candidate.id(), Integer.MAX_VALUE))
                        .thenComparing(candidate -> candidate.id().toString()))
                .toList();
    }

    private void addClusterIfRaw(
            List<RawVideoAggregationCluster> clusters,
            Iterable<AggregationGraphCandidate> members) {
        List<RawVideoAggregationRecord> rawMembers = new ArrayList<>();
        List<UUID> unifiedIds = new ArrayList<>();
        for (AggregationGraphCandidate member : members) {
            if (member instanceof AggregationGraphCandidate.RawNode rawNode) {
                rawMembers.add(rawNode.rawVideo());
            } else if (member instanceof AggregationGraphCandidate.UnifiedNode unifiedNode) {
                unifiedIds.add(unifiedNode.unifiedVideo().id());
            }
        }
        if (rawMembers.isEmpty()) {
            return;
        }
        clusters.add(new RawVideoAggregationCluster(rawMembers.getFirst(), rawMembers, unifiedIds));
    }

    private boolean hasAnyExternalId(AggregationGraphCandidate candidate) {
        if (candidate instanceof AggregationGraphCandidate.RawNode rawNode) {
            RawVideoAggregationRecord raw = rawNode.rawVideo();
            return isValidExternalId(raw.doubanId())
                    || isValidExternalId(raw.tmdbId())
                    || isValidExternalId(raw.imdbId())
                    || isValidExternalId(raw.rottenTomatoesId());
        }
        if (candidate instanceof AggregationGraphCandidate.UnifiedNode unifiedNode) {
            UnifiedVideoAggregationCandidate unified = unifiedNode.unifiedVideo();
            return isValidExternalId(unified.doubanId())
                    || isValidExternalId(unified.tmdbId())
                    || isValidExternalId(unified.imdbId())
                    || isValidExternalId(unified.rottenTomatoesId());
        }
        return false;
    }

    private int titleLength(AggregationGraphCandidate candidate) {
        String title = null;
        if (candidate instanceof AggregationGraphCandidate.RawNode rawNode) {
            title = rawNode.rawVideo().title();
        } else if (candidate instanceof AggregationGraphCandidate.UnifiedNode unifiedNode) {
            title = unifiedNode.unifiedVideo().title();
        }
        return StringUtils.hasText(title) ? title.length() : 0;
    }

    private boolean isValidExternalId(String value) {
        return StringUtils.hasText(value) && !"0".equals(value.trim()) && !"0.0".equals(value.trim());
    }

    private String categoryBucket(AggregationGraphCandidate candidate) {
        return StringUtils.hasText(candidate.categoryName())
                ? candidate.categoryName().trim()
                : "UNKNOWN_CATEGORY";
    }

    private boolean isYearBucketCompatible(String left, String right) {
        int leftYear = parseYear(left);
        int rightYear = parseYear(right);
        if (leftYear == 0 || rightYear == 0) {
            return leftYear == rightYear;
        }
        return Math.abs(leftYear - rightYear) <= 1;
    }

    private int parseYear(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
