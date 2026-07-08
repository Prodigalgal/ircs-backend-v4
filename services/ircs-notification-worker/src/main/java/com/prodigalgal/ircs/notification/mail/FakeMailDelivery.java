package com.prodigalgal.ircs.notification.mail;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class FakeMailDelivery implements MailDelivery {

    private final NotificationMailProperties properties;
    private final FakeMailSink sink;

    @Override
    public void deliver(RenderedMail mail, Optional<MailCredential> credential) {
        if (properties.isFakeFailureEnabled()) {
            throw new RuntimeException("Fake mail delivery failed");
        }
        sink.record(mail);
        log.info("Fake mail sink accepted email to {}", mail.to());
    }
}
