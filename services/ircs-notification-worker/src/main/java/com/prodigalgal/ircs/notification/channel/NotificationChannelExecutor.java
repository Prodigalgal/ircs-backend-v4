package com.prodigalgal.ircs.notification.channel;

import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;

public interface NotificationChannelExecutor {

    NotificationChannel channel();

    NotificationChannelExecution execute(NotificationCommand command, String recipient);
}
