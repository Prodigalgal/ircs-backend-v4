package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ImageDeletionConsumer {

    private final StorageUuidMessageParser messageParser;
    private final CoverImagePhysicalDeletionService deletionService;

    @RabbitListener(queues = QueueTopic.Names.STORAGE_Q_DELETE)
    public void onMessage(Message message) {
        UUID imageId = messageParser.parse(message);
        log.info("Processing cover image physical deletion: {}", imageId);
        deletionService.delete(imageId);
    }
}
