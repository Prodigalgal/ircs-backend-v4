package com.prodigalgal.ircs.metadata.dispatch.messaging;

import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetadataEnrichPublisher {

    private final RuntimeWorkQueue workQueue;

    public void publish(UUID rawVideoId, String pipelineVersion) {
        publish(rawVideoId, pipelineVersion, Duration.ZERO);
    }

    public void publish(UUID rawVideoId, String pipelineVersion, Duration delay) {
        workQueue.submitAfterCommit(request(rawVideoId, pipelineVersion), delay);
    }

    private static RuntimeWorkItemRequest request(UUID rawVideoId, String pipelineVersion) {
        return new RuntimeWorkItemRequest(
                PipelineRuntimeWorkTypes.ENRICH_METADATA,
                PipelineRuntimeWorkTypes.enrichTaskId(rawVideoId, pipelineVersion),
                rawVideoId.toString(),
                PipelineRuntimeWorkTypes.normalizeVersion(pipelineVersion),
                "");
    }
}
