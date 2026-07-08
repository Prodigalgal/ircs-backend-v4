package com.prodigalgal.ircs.task.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.contracts.task.TaskDetailDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskDetailMessage;
import com.prodigalgal.ircs.contracts.task.TaskMasterDoneMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageDiscoveredMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageFailedMessage;
import com.prodigalgal.ircs.contracts.task.TaskPageMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskQueuePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishPage(TaskPageMessage message) {
        publish(QueueTopic.TASK_PAGE, message);
    }

    public void publishDetail(TaskDetailMessage message) {
        publish(QueueTopic.TASK_DETAIL, message);
    }

    public void publishPageDiscovered(TaskPageDiscoveredMessage message) {
        publish(QueueTopic.TASK_PAGE_DISCOVERED, message);
    }

    public void publishPageFailed(TaskPageFailedMessage message) {
        publish(QueueTopic.TASK_PAGE_FAILED, message);
    }

    public void publishDetailDone(TaskDetailDoneMessage message) {
        publish(QueueTopic.TASK_DETAIL_DONE, message);
    }

    public void publishMasterDone(TaskMasterDoneMessage message) {
        publish(QueueTopic.TASK_MASTER_DONE, message);
    }

    private void publish(QueueTopic topic, Object payload) {
        try {
            rabbitTemplate.convertAndSend(
                    topic.exchange(),
                    topic.routingKey(),
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize task queue payload: " + topic.name(), ex);
        }
    }
}
