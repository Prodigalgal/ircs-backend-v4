package com.prodigalgal.ircs.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.junit.jupiter.api.Test;

class QueueOwnershipTest {

    @Test
    void everyTopicHasOneOwner() {
        assertEquals(QueueTopic.values().length, QueueOwnership.OWNER_BY_TOPIC.size());
        for (QueueTopic topic : QueueTopic.values()) {
            assertTrue(QueueOwnership.OWNER_BY_TOPIC.containsKey(topic), topic.name());
        }
    }

    @Test
    void notificationWorkerOwnsMailQueue() {
        assertEquals("ircs-notification-worker", QueueOwnership.OWNER_BY_TOPIC.get(QueueTopic.SEND_MAIL));
        assertEquals("ircs-notification-worker", QueueOwnership.OWNER_BY_TOPIC.get(QueueTopic.DISPATCH_NOTIFICATION));
    }

    @Test
    void storageServiceOwnsImageDownloadQueue() {
        assertEquals("ircs-storage-service", QueueOwnership.OWNER_BY_TOPIC.get(QueueTopic.DOWNLOAD_IMAGE));
    }
}
