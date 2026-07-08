package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class FakeMailDeliveryTest {

    @Test
    void recordsIntendedMailWithoutSmtpNetwork() {
        NotificationMailProperties properties = new NotificationMailProperties();
        FakeMailSink sink = new FakeMailSink();
        FakeMailDelivery delivery = new FakeMailDelivery(properties, sink);
        RenderedMail mail = new RenderedMail(
                "noreply@example.invalid",
                "codex@example.invalid",
                "subject",
                "content",
                true);

        delivery.deliver(mail, Optional.empty());

        assertEquals(1, sink.messages().size());
        FakeMailSink.SentMail sent = sink.messages().getFirst();
        assertEquals("noreply@example.invalid", sent.from());
        assertEquals("codex@example.invalid", sent.to());
        assertEquals("subject", sent.subject());
        assertEquals("content", sent.content());
        org.assertj.core.api.Assertions.assertThat(sent.html()).isTrue();
    }

    @Test
    void fakeFailureRethrowsWithoutRecordingMail() {
        NotificationMailProperties properties = new NotificationMailProperties();
        properties.setFakeFailureEnabled(true);
        FakeMailSink sink = new FakeMailSink();
        FakeMailDelivery delivery = new FakeMailDelivery(properties, sink);
        RenderedMail mail = new RenderedMail(
                "",
                "codex@example.invalid",
                "subject",
                "content",
                false);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> delivery.deliver(mail, Optional.empty()));

        assertEquals("Fake mail delivery failed", thrown.getMessage());
        assertEquals(0, sink.messages().size());
    }
}
