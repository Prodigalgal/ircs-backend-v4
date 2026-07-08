package com.prodigalgal.ircs.content.image;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.content.listener", name = "enabled", havingValue = "true")
public class ImageUnlinkConsumer {

    private final ContentUuidMessageParser messageParser;
    private final CoverImageUnlinkService unlinkService;

    @RabbitListener(queues = QueueTopic.Names.STORAGE_Q_UNLINK)
    public void onMessage(Message message) {
        UUID imageId = messageParser.parse(message);
        log.info("Processing cover image unlink command: {}", imageId);
        unlinkService.unlink(imageId);
    }
}
