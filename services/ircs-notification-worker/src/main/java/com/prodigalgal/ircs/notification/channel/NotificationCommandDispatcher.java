package com.prodigalgal.ircs.notification.channel;

import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class NotificationCommandDispatcher {

    private final Map<NotificationChannel, NotificationChannelExecutor> executors;

    NotificationCommandDispatcher(List<NotificationChannelExecutor> executors) {
        this.executors = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelExecutor executor : executors) {
            this.executors.put(executor.channel(), executor);
        }
    }

    public List<NotificationChannelExecution> dispatch(NotificationCommand command) {
        validate(command);
        NotificationChannelExecutor executor = executors.get(command.getChannel());
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported notification channel: " + command.getChannel());
        }
        return command.getRecipients().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(recipient -> executor.execute(command, recipient))
                .toList();
    }

    private void validate(NotificationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Notification command is required");
        }
        if (command.getChannel() == null) {
            throw new IllegalArgumentException("Notification channel is required");
        }
        if (command.getRecipients() == null || command.getRecipients().stream().noneMatch(StringUtils::hasText)) {
            throw new IllegalArgumentException("At least one notification recipient is required");
        }
        if (!StringUtils.hasText(command.getContent()) && !StringUtils.hasText(command.getTemplateCode())) {
            throw new IllegalArgumentException("Notification content or templateCode is required");
        }
    }
}
