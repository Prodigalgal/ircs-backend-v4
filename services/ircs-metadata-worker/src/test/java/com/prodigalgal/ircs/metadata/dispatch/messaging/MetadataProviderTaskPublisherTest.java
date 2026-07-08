package com.prodigalgal.ircs.metadata.dispatch.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.pipeline.PipelineRuntimeWorkTypes;
import com.prodigalgal.ircs.common.work.RuntimeWorkItemRequest;
import com.prodigalgal.ircs.common.work.RuntimeWorkQueue;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetadataProviderTaskPublisherTest {

    @Test
    void publishesProviderFetchAsValkeyTasks() {
        RuntimeWorkQueue workQueue = org.mockito.Mockito.mock(RuntimeWorkQueue.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MetadataProviderTaskPublisher publisher = new MetadataProviderTaskPublisher(workQueue, objectMapper);
        UUID videoId = UUID.randomUUID();
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(videoId)
                .pipelineVersion("hash-v1")
                .title("test")
                .build();

        publisher.publish(ProviderType.DOUBAN, context);
        publisher.publish(ProviderType.TMDB, context);
        publisher.publish(ProviderType.ROTTEN_TOMATOES, context);

        ArgumentCaptor<RuntimeWorkItemRequest> requestCaptor = ArgumentCaptor.forClass(RuntimeWorkItemRequest.class);
        verify(workQueue, org.mockito.Mockito.times(3)).submitAfterCommit(requestCaptor.capture());
        List<RuntimeWorkItemRequest> requests = requestCaptor.getAllValues();
        assertEquals(List.of(
                PipelineRuntimeWorkTypes.providerTaskId("DOUBAN", videoId, "hash-v1"),
                PipelineRuntimeWorkTypes.providerTaskId("TMDB", videoId, "hash-v1"),
                PipelineRuntimeWorkTypes.providerTaskId("ROTTEN_TOMATOES", videoId, "hash-v1")),
                requests.stream().map(RuntimeWorkItemRequest::taskId).toList());
        assertTrue(requests.stream().allMatch(request ->
                PipelineRuntimeWorkTypes.METADATA_PROVIDER.equals(request.taskType())
                        && videoId.toString().equals(request.aggregateId())
                        && "hash-v1".equals(request.version())));
        assertTrue(requests.get(0).payload().contains("\"provider\":\"DOUBAN\""));
        assertTrue(requests.get(1).payload().contains("\"provider\":\"TMDB\""));
        assertTrue(requests.get(2).payload().contains("\"provider\":\"ROTTEN_TOMATOES\""));
    }

    @Test
    void omitsDerivedFullTitleFromValkeyPayload() throws Exception {
        RuntimeWorkQueue workQueue = org.mockito.Mockito.mock(RuntimeWorkQueue.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MetadataProviderTaskPublisher publisher = new MetadataProviderTaskPublisher(workQueue, objectMapper);
        UUID videoId = UUID.randomUUID();
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(videoId)
                .pipelineVersion("hash-v1")
                .title("test")
                .subtitle("subtitle")
                .build();

        publisher.publish(ProviderType.TMDB, context);

        ArgumentCaptor<RuntimeWorkItemRequest> requestCaptor = ArgumentCaptor.forClass(RuntimeWorkItemRequest.class);
        verify(workQueue).submitAfterCommit(requestCaptor.capture());
        JsonNode payload = objectMapper.readTree(requestCaptor.getValue().payload());
        Assertions.assertFalse(payload.path("context").has("fullTitle"));
    }
}
