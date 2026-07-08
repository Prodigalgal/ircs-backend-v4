package com.prodigalgal.ircs.contracts.queue;

public enum QueueTopic {
    INGEST_VIDEO(Names.INGEST_Q, Names.INGEST_X, "video.upsert"),
    PLAYLIST_SYNC(Names.PROCESS_Q_PLAYLIST, Names.PROCESS_X, "video.playlist_sync"),
    DOWNLOAD_IMAGE(Names.STORAGE_Q_IMAGE, Names.STORAGE_X, "image.download"),
    CMD_IMAGE_UNLINK(Names.STORAGE_Q_UNLINK, Names.STORAGE_X, "image.cmd.unlink"),
    EVENT_IMAGE_UNLINKED(Names.STORAGE_Q_DELETE, Names.STORAGE_X, "image.event.unlinked"),
    SEND_MAIL(Names.NOTIFICATION_Q_MAIL, Names.NOTIFICATION_X, "notification.mail"),
    DISPATCH_NOTIFICATION(Names.NOTIFICATION_Q_COMMAND, Names.NOTIFICATION_X, "notification.command"),
    WATCH_PROGRESS(Names.INTERACTION_Q_PROGRESS, Names.INTERACTION_X, "interaction.progress"),
    TASK_PAGE(Names.TASK_Q_PAGE, Names.TASK_X, "task.page"),
    TASK_DETAIL(Names.TASK_Q_DETAIL, Names.TASK_X, "task.detail"),
    TASK_PAGE_DISCOVERED(Names.TASK_Q_PAGE_DISCOVERED, Names.TASK_X, "task.page.discovered"),
    TASK_PAGE_FAILED(Names.TASK_Q_PAGE_FAILED, Names.TASK_X, "task.page.failed"),
    TASK_DETAIL_DONE(Names.TASK_Q_DETAIL_DONE, Names.TASK_X, "task.detail.done"),
    TASK_MASTER_DONE(Names.TASK_Q_MASTER_DONE, Names.TASK_X, "task.master.done");

    private final String queueName;
    private final String exchange;
    private final String routingKey;
    private final String dlqName;
    private final String retryName;
    private final String retryRoutingKey;

    QueueTopic(String queueName, String exchange, String routingKey) {
        this.queueName = queueName;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.dlqName = queueName + ".dlq";
        this.retryName = queueName + ".retry";
        this.retryRoutingKey = routingKey + ".retry";
    }

    public String queueName() {
        return queueName;
    }

    public String exchange() {
        return exchange;
    }

    public String routingKey() {
        return routingKey;
    }

    public String dlqName() {
        return dlqName;
    }

    public String retryName() {
        return retryName;
    }

    public String retryRoutingKey() {
        return retryRoutingKey;
    }

    public static final class Names {
        public static final String INGEST_X = "x.ingest";
        public static final String PROCESS_X = "x.process";
        public static final String STORAGE_X = "x.storage";
        public static final String NOTIFICATION_X = "x.notification";
        public static final String INTERACTION_X = "x.interaction";
        public static final String TASK_X = "x.task";
        public static final String DLX = "x.dlx";
        public static final String DOMAIN_EVENT_X = "x.domain.events";

        public static final String INGEST_Q = "q.ingest.video";
        public static final String PROCESS_Q_PLAYLIST = "q.process.playlist_sync";
        public static final String STORAGE_Q_IMAGE = "q.storage.image";
        public static final String STORAGE_Q_UNLINK = "q.storage.image_unlink";
        public static final String STORAGE_Q_DELETE = "q.storage.image_delete";
        public static final String NOTIFICATION_Q_MAIL = "q.notification.mail";
        public static final String NOTIFICATION_Q_COMMAND = "q.notification.command";
        public static final String INTERACTION_Q_PROGRESS = "q.interaction.watch_progress";
        public static final String TASK_Q_PAGE = "q.task.page";
        public static final String TASK_Q_DETAIL = "q.task.detail";
        public static final String TASK_Q_PAGE_DISCOVERED = "q.task.page.discovered";
        public static final String TASK_Q_PAGE_FAILED = "q.task.page.failed";
        public static final String TASK_Q_DETAIL_DONE = "q.task.detail.done";
        public static final String TASK_Q_MASTER_DONE = "q.task.master.done";

        private Names() {
        }
    }
}
