package com.prodigalgal.ircs.ops.infrastructure.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record RabbitManagementQueues(
        JsonNode raw,
        List<RabbitManagementQueueSnapshot> snapshots
) {
    public RabbitManagementQueues {
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    public Map<String, RabbitManagementQueueSnapshot> byName() {
        return snapshots.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RabbitManagementQueueSnapshot::name,
                        Function.identity(),
                        (left, right) -> right));
    }
}
