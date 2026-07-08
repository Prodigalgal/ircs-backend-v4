package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NormalizeVideoPublisher {

    private final RuntimeWorkQueue workQueue;

    public void publish(UUID rawVideoId, String pipelineVersion) {
        workQueue.submitAfterCommit(request(rawVideoId, pipelineVersion));
    }

    public void publishNow(UUID rawVideoId, String pipelineVersion) {
        workQueue.submit(request(rawVideoId, pipelineVersion));
    }

    private static RuntimeWorkItemRequest request(UUID rawVideoId, String pipelineVersion) {
        return new RuntimeWorkItemRequest(
                PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                PipelineRuntimeWorkTypes.normalizeTaskId(rawVideoId, pipelineVersion),
                rawVideoId.toString(),
                PipelineRuntimeWorkTypes.normalizeVersion(pipelineVersion),
                "");
    }
}
