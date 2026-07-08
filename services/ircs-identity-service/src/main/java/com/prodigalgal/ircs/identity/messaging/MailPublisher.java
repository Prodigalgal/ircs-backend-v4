package com.prodigalgal.ircs.identity.messaging;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MailPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(MailMessageDTO message) {
        QueueTopic topic = QueueTopic.SEND_MAIL;
        rabbitTemplate.convertAndSend(topic.exchange(), topic.routingKey(), message);
    }
}
