package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StorageCommandPublisher {

    private static final String IMAGE_DOWNLOAD_ENABLED_KEY = "app.storage.image.download.enabled";

    private final RabbitTemplate rabbitTemplate;
    private final SystemConfigRepository configRepository;

    @Value("${app.storage.image.download.enabled:true}")
    private boolean imageDownloadEnabledByDeployment;

    public void publishImageUnlink(UUID imageId) {
        publish(QueueTopic.CMD_IMAGE_UNLINK, imageId);
    }

    public void publishImageDownload(UUID imageId) {
        if (!imageDownloadEnabled()) {
            log.debug("Skipped cover image download command because image download is disabled: imageId={}", imageId);
            return;
        }
        publish(QueueTopic.DOWNLOAD_IMAGE, imageId);
    }

    private void publish(QueueTopic topic, UUID imageId) {
        rabbitTemplate.convertAndSend(topic.exchange(), topic.routingKey(), imageId.toString());
    }

    private boolean imageDownloadEnabled() {
        return configRepository.findValue(IMAGE_DOWNLOAD_ENABLED_KEY)
                .map(value -> parseBoolean(value, imageDownloadEnabledByDeployment))
                .orElse(imageDownloadEnabledByDeployment);
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }
}
