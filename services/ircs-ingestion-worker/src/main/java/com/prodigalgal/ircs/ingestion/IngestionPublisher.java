package com.prodigalgal.ircs.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.contracts.ingestion.PlaylistSyncMessage;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IngestionPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkQueue workQueue;

    public void publishPlaylistSync(PlaylistSyncMessage message) {
        publishJson(QueueTopic.PLAYLIST_SYNC, message);
    }

    public void publishNormalize(UUID rawVideoId, String pipelineVersion) {
        workQueue.submitAfterCommit(new RuntimeWorkItemRequest(
                PipelineRuntimeWorkTypes.NORMALIZE_VIDEO,
                PipelineRuntimeWorkTypes.normalizeTaskId(rawVideoId, pipelineVersion),
                rawVideoId.toString(),
                PipelineRuntimeWorkTypes.normalizeVersion(pipelineVersion),
                ""));
    }

    private void publishJson(QueueTopic topic, Object payload) {
        try {
            rabbitTemplate.convertAndSend(topic.exchange(), topic.routingKey(), objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize ingestion payload", ex);
        }
    }
}
