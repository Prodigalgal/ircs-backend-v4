package com.prodigalgal.ircs.storage.image;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class StorageCommandPublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final SystemConfigRepository configRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final StorageCommandPublisher publisher = new StorageCommandPublisher(rabbitTemplate, configRepository);

    @Test
    void skipsImageDownloadCommandWhenDisabledBySystemConfig() {
        UUID imageId = UUID.randomUUID();
        ReflectionTestUtils.setField(publisher, "imageDownloadEnabledByDeployment", true);
        when(configRepository.findValue("app.storage.image.download.enabled")).thenReturn(Optional.of("false"));

        publisher.publishImageDownload(imageId);

        verify(rabbitTemplate, never()).convertAndSend(
                eq(QueueTopic.DOWNLOAD_IMAGE.exchange()),
                eq(QueueTopic.DOWNLOAD_IMAGE.routingKey()),
                eq(imageId.toString()));
    }

    @Test
    void publishesImageDownloadCommandWhenEnabled() {
        UUID imageId = UUID.randomUUID();
        ReflectionTestUtils.setField(publisher, "imageDownloadEnabledByDeployment", false);
        when(configRepository.findValue("app.storage.image.download.enabled")).thenReturn(Optional.of("true"));

        publisher.publishImageDownload(imageId);

        verify(rabbitTemplate).convertAndSend(
                QueueTopic.DOWNLOAD_IMAGE.exchange(),
                QueueTopic.DOWNLOAD_IMAGE.routingKey(),
                imageId.toString());
    }

    @Test
    void imageUnlinkCommandIsNotGatedByDownloadSwitch() {
        UUID imageId = UUID.randomUUID();
        when(configRepository.findValue("app.storage.image.download.enabled")).thenReturn(Optional.of("false"));

        publisher.publishImageUnlink(imageId);

        verify(rabbitTemplate).convertAndSend(
                QueueTopic.CMD_IMAGE_UNLINK.exchange(),
                QueueTopic.CMD_IMAGE_UNLINK.routingKey(),
                imageId.toString());
    }
}
