package com.prodigalgal.ircs.notification.mail;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;

public interface NotificationMailService {
    void send(MailMessageDTO message);

    default void send(MailMessageDTO message, String correlationId) {
        send(message);
    }
}
