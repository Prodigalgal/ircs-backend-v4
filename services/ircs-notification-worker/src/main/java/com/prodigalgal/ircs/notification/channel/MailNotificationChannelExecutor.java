package com.prodigalgal.ircs.notification.channel;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.notification.mail.NotificationMailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MailNotificationChannelExecutor implements NotificationChannelExecutor {

    private final NotificationMailService mailService;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.MAIL;
    }

    @Override
    public NotificationChannelExecution execute(NotificationCommand command, String recipient) {
        MailMessageDTO message = MailMessageDTO.builder()
                .to(recipient)
                .subject(command.getSubject())
                .content(command.getContent())
                .html(command.isHtml())
                .templateCode(command.getTemplateCode())
                .variables(command.getVariables())
                .build();
        mailService.send(message, command.getCorrelationId());
        return NotificationChannelExecution.delivered(channel().name(), recipient, "mail accepted by delivery layer");
    }
}
