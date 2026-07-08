package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.contracts.interaction.WatchProgressMessage;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.interaction.listener", name = "enabled", havingValue = "true")
public class WatchProgressConsumer {

    private final WatchProgressMessageParser messageParser;
    private final WatchProgressBatchService batchService;

    @RabbitListener(queues = QueueTopic.Names.INTERACTION_Q_PROGRESS)
    public void onMessage(Message amqpMessage) {
        WatchProgressMessage message = messageParser.parse(amqpMessage);
        log.debug("Processing watch progress message: {}", message);
        batchService.batchUpsert(List.of(message));
    }
}
