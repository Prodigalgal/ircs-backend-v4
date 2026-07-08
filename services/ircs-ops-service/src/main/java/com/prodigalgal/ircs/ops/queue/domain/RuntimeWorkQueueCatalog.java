package com.prodigalgal.ircs.ops.queue.domain;

import com.prodigalgal.ircs.common.aggregation.AggregationWorkTypes;
import com.prodigalgal.ircs.common.audit.AuditReplicationWorkTypes;
import com.prodigalgal.ircs.common.magnet.MagnetWorkTypes;
import com.prodigalgal.ircs.common.normalization.LlmCleaningWorkTypes;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkTypes;
import com.prodigalgal.ircs.common.storage.StorageWorkTypes;
import java.util.List;
import java.util.Optional;

public final class RuntimeWorkQueueCatalog {

    private static final List<RuntimeWorkQueueDescriptor> DESCRIPTORS = List.of(
            new RuntimeWorkQueueDescriptor("SEARCH_RAW", "Search Raw", SearchSyncWorkTypes.RAW, "#2563eb", "#60a5fa"),
            new RuntimeWorkQueueDescriptor("SEARCH_UNIFIED", "Search Unified", SearchSyncWorkTypes.UNIFIED, "#1d4ed8", "#3b82f6"),
            new RuntimeWorkQueueDescriptor("AGGREGATION", "Aggregation", AggregationWorkTypes.RAW_VIDEO, "#c2410c", "#fb923c"),
            new RuntimeWorkQueueDescriptor("PIPELINE_NORMALIZE", "Pipeline Normalize", PipelineRuntimeWorkTypes.NORMALIZE_VIDEO, "#16a34a", "#4ade80"),
            new RuntimeWorkQueueDescriptor("PIPELINE_METADATA_DISPATCH", "Pipeline Metadata Dispatch", PipelineRuntimeWorkTypes.ENRICH_METADATA, "#0e7490", "#67e8f9"),
            new RuntimeWorkQueueDescriptor("PIPELINE_METADATA_PROVIDER", "Pipeline Metadata Provider", PipelineRuntimeWorkTypes.METADATA_PROVIDER, "#7c3aed", "#a78bfa"),
            new RuntimeWorkQueueDescriptor("LLM_CLEANING", "LLM Cleaning", LlmCleaningWorkTypes.RAW_TERM, "#9333ea", "#c084fc"),
            new RuntimeWorkQueueDescriptor("STORAGE_AVATAR", "Avatar R2", StorageWorkTypes.AVATAR_SYNC, "#0891b2", "#22d3ee"),
            new RuntimeWorkQueueDescriptor("STORAGE_COVER", "Cover R2", StorageWorkTypes.COVER_R2_SYNC, "#0f766e", "#2dd4bf"),
            new RuntimeWorkQueueDescriptor("AUDIT_ES", "Audit ES", AuditReplicationWorkTypes.ES_REPLICATION, "#4b5563", "#9ca3af"),
            new RuntimeWorkQueueDescriptor("MAGNET_SEARCH", "Magnet Search", MagnetWorkTypes.SEARCH, "#be123c", "#fb7185"));

    private RuntimeWorkQueueCatalog() {
    }

    public static List<RuntimeWorkQueueDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    public static Optional<RuntimeWorkQueueDescriptor> findByTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return Optional.empty();
        }
        String normalized = taskType.trim();
        return DESCRIPTORS.stream()
                .filter(descriptor -> descriptor.taskType().equals(normalized))
                .findFirst();
    }
}
