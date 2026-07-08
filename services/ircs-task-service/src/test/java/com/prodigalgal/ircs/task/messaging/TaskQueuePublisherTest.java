package com.prodigalgal.ircs.task.messaging;

import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import com.prodigalgal.ircs.contracts.task.TaskScrapeOptions;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class TaskQueuePublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final TaskQueuePublisher publisher = new TaskQueuePublisher(rabbitTemplate, objectMapper);

    @Test
    void publishesPageMessageToTaskExchange() throws Exception {
        UUID masterTaskId = UUID.randomUUID();
        UUID pageTaskId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();

        publisher.publishPage(new TaskPageMessage(
                masterTaskId,
                pageTaskId,
                dataSourceId,
                7,
                false,
                0,
                new TaskScrapeOptions("codex", null, null, null, true, false, null, null, null, null, null, null, 0, false),
                masterTaskId.toString(),
                Instant.parse("2026-06-13T00:00:00Z")));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.Mockito.eq(QueueTopic.Names.TASK_X),
                org.mockito.Mockito.eq("task.page"),
                payloadCaptor.capture());
        TaskPageMessage payload = objectMapper.readValue(payloadCaptor.getValue(), TaskPageMessage.class);
        org.junit.jupiter.api.Assertions.assertEquals(masterTaskId, payload.masterTaskId());
        org.junit.jupiter.api.Assertions.assertEquals(pageTaskId, payload.pageTaskId());
        org.junit.jupiter.api.Assertions.assertEquals(dataSourceId, payload.dataSourceId());
        org.junit.jupiter.api.Assertions.assertEquals(7, payload.pageNumber());
        org.junit.jupiter.api.Assertions.assertEquals("codex", payload.options().keyword());
    }
}
