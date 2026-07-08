package com.prodigalgal.ircs.aggregation;

import java.util.UUID;

public sealed interface AggregationGraphCandidate
        permits AggregationGraphCandidate.RawNode, AggregationGraphCandidate.UnifiedNode {

    UUID id();

    String year();

    String categoryName();

    static RawNode raw(RawVideoAggregationRecord rawVideo) {
        return new RawNode(rawVideo);
    }

    static UnifiedNode unified(UnifiedVideoAggregationCandidate unifiedVideo) {
        return new UnifiedNode(unifiedVideo);
    }

    record RawNode(RawVideoAggregationRecord rawVideo) implements AggregationGraphCandidate {
        @Override
        public UUID id() {
            return rawVideo.id();
        }

        @Override
        public String year() {
            return rawVideo.year();
        }

        @Override
        public String categoryName() {
            return rawVideo.categoryName();
        }
    }

    record UnifiedNode(UnifiedVideoAggregationCandidate unifiedVideo) implements AggregationGraphCandidate {
        @Override
        public UUID id() {
            return unifiedVideo.id();
        }

        @Override
        public String year() {
            return unifiedVideo.year();
        }

        @Override
        public String categoryName() {
            return unifiedVideo.categoryName();
        }
    }
}
