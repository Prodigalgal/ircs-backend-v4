package com.prodigalgal.ircs.messaging;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.Map;

public final class QueueOwnership {

    public static final Map<QueueTopic, String> OWNER_BY_TOPIC = Map.ofEntries(
            Map.entry(QueueTopic.INGEST_VIDEO, "ircs-ingestion-worker"),
            Map.entry(QueueTopic.PLAYLIST_SYNC, "ircs-ingestion-worker"),
            Map.entry(QueueTopic.DOWNLOAD_IMAGE, "ircs-storage-service"),
            Map.entry(QueueTopic.CMD_IMAGE_UNLINK, "ircs-content-service"),
            Map.entry(QueueTopic.EVENT_IMAGE_UNLINKED, "ircs-storage-service"),
            Map.entry(QueueTopic.SEND_MAIL, "ircs-notification-worker"),
            Map.entry(QueueTopic.DISPATCH_NOTIFICATION, "ircs-notification-worker"),
            Map.entry(QueueTopic.WATCH_PROGRESS, "ircs-interaction-service"),
            Map.entry(QueueTopic.TASK_PAGE, "ircs-scraper-service"),
            Map.entry(QueueTopic.TASK_DETAIL, "ircs-scraper-service"),
            Map.entry(QueueTopic.TASK_PAGE_DISCOVERED, "ircs-task-service"),
            Map.entry(QueueTopic.TASK_PAGE_FAILED, "ircs-task-service"),
            Map.entry(QueueTopic.TASK_DETAIL_DONE, "ircs-task-service"),
            Map.entry(QueueTopic.TASK_MASTER_DONE, "ircs-task-service")
    );

    private QueueOwnership() {
    }
}
