package com.prodigalgal.ircs.notification.channel;

public record NotificationChannelExecution(
        String channel,
        String recipient,
        String status,
        String detail) {

    public static NotificationChannelExecution delivered(String channel, String recipient, String detail) {
        return new NotificationChannelExecution(channel, recipient, "SENT", detail);
    }

    public static NotificationChannelExecution skipped(String channel, String recipient, String detail) {
        return new NotificationChannelExecution(channel, recipient, "SKIPPED", detail);
    }
}
