package com.prodigalgal.ircs.notification.mail;

public interface MailSendHistoryWriter {

    void record(MailSendHistoryEvent event);

    static MailSendHistoryWriter noop() {
        return event -> {
        };
    }
}
