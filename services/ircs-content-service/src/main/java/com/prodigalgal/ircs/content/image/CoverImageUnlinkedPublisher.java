package com.prodigalgal.ircs.content.image;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CoverImageUnlinkedPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(UUID imageId) {
        QueueTopic topic = QueueTopic.EVENT_IMAGE_UNLINKED;
        rabbitTemplate.convertAndSend(topic.exchange(), topic.routingKey(), imageId.toString());
    }
}
