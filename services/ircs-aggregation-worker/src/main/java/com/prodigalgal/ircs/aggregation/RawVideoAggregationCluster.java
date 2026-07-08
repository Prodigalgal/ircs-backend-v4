package com.prodigalgal.ircs.aggregation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public record RawVideoAggregationCluster(
        RawVideoAggregationRecord leader,
        List<RawVideoAggregationRecord> members,
        List<UUID> contextUnifiedVideoIds) {

    public RawVideoAggregationCluster {
        if (leader == null) {
            throw new IllegalArgumentException("leader is required");
        }
        LinkedHashSet<RawVideoAggregationRecord> orderedMembers = new LinkedHashSet<>();
        orderedMembers.add(leader);
        if (members != null) {
            orderedMembers.addAll(members);
        }
        members = List.copyOf(orderedMembers);
        contextUnifiedVideoIds = contextUnifiedVideoIds == null
                ? List.of()
                : contextUnifiedVideoIds.stream()
                        .filter(id -> id != null)
                        .distinct()
                        .toList();
    }

    public RawVideoAggregationCluster(RawVideoAggregationRecord leader, List<RawVideoAggregationRecord> members) {
        this(leader, members, List.of());
    }

    public List<UUID> rawVideoIds() {
        return members.stream()
                .map(RawVideoAggregationRecord::id)
                .toList();
    }

    public boolean isMultiRawCluster() {
        return members.size() > 1;
    }
}
