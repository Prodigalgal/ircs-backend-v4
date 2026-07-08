package com.prodigalgal.ircs.ops.queue.domain;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;

public record QueueTopicDescriptor(
        QueueTopic topic,
        String displayName,
        String group,
        String color
) {
    public static QueueTopicDescriptor from(QueueTopic topic) {
        return switch (topic) {
            case INGEST_VIDEO -> descriptor(topic, "视频入库", "Ingestion", "#2563eb");
            case PLAYLIST_SYNC -> descriptor(topic, "播放列表同步", "Ingestion", "#0891b2");
            case DOWNLOAD_IMAGE -> descriptor(topic, "图片下载", "Storage", "#4f46e5");
            case CMD_IMAGE_UNLINK -> descriptor(topic, "图片解绑", "Storage", "#475569");
            case EVENT_IMAGE_UNLINKED -> descriptor(topic, "图片删除", "Storage", "#64748b");
            case SEND_MAIL -> descriptor(topic, "邮件通知", "Notification", "#ca8a04");
            case DISPATCH_NOTIFICATION -> descriptor(topic, "通知命令", "Notification", "#d97706");
            case WATCH_PROGRESS -> descriptor(topic, "观看进度", "Interaction", "#0284c7");
            case TASK_PAGE -> descriptor(topic, "采集 Page Task", "Task", "#0f766e");
            case TASK_DETAIL -> descriptor(topic, "采集 Detail Task", "Task", "#2563eb");
            case TASK_PAGE_DISCOVERED -> descriptor(topic, "Page 发现事件", "Task", "#16a34a");
            case TASK_PAGE_FAILED -> descriptor(topic, "Page 失败事件", "Task", "#dc2626");
            case TASK_DETAIL_DONE -> descriptor(topic, "Detail 完成事件", "Task", "#22c55e");
            case TASK_MASTER_DONE -> descriptor(topic, "Master 完成事件", "Task", "#15803d");
        };
    }

    private static QueueTopicDescriptor descriptor(QueueTopic topic, String displayName, String group, String color) {
        return new QueueTopicDescriptor(topic, displayName, group, color);
    }
}
