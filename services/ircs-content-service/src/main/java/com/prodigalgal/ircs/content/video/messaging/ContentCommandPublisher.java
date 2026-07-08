package com.prodigalgal.ircs.content.video.messaging;

import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.search.SearchSyncWorkPublisher;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentCommandPublisher {

    private final SearchSyncWorkPublisher searchSyncWorkPublisher;
    private final RuntimeWorkQueue workQueue;

    public void publishRawSearch(UUID rawVideoId, SyncOperation operation) {
        searchSyncWorkPublisher.enqueue(rawVideoId, SearchEntityType.RAW_VIDEO, operation);
    }

    public void publishUnifiedSearch(UUID unifiedVideoId, SyncOperation operation) {
        searchSyncWorkPublisher.enqueue(unifiedVideoId, SearchEntityType.UNIFIED_VIDEO, operation);
    }

    public void publishNormalize(UUID rawVideoId, String pipelineVersion) {
        workQueue.submitAfterCommit(request(
                PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                PipelineRuntimeWorkTypes.normalizeTaskId(rawVideoId, pipelineVersion),
                rawVideoId,
                pipelineVersion));
    }

    public void publishEnrich(UUID rawVideoId, String pipelineVersion) {
        workQueue.submitAfterCommit(request(
                PipelineRuntimeWorkTypes.ENRICH_METADATA,
                PipelineRuntimeWorkTypes.enrichTaskId(rawVideoId, pipelineVersion),
                rawVideoId,
                pipelineVersion));
    }

    private static RuntimeWorkItemRequest request(
            String taskType,
            String taskId,
            UUID rawVideoId,
            String pipelineVersion) {
        return new RuntimeWorkItemRequest(
                taskType,
                taskId,
                rawVideoId.toString(),
                PipelineRuntimeWorkTypes.normalizeVersion(pipelineVersion),
                "");
    }
}
