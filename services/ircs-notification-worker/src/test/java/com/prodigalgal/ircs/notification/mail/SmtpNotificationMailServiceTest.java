package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@ExtendWith(MockitoExtension.class)
class SmtpNotificationMailServiceTest {

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private MailCredentialResolver credentialResolver;

    @Mock
    private SmtpMailDelivery smtpDelivery;

    @Mock
    private FakeMailDelivery fakeDelivery;

    @Mock
    private NotificationMailConfigValues configValues;

    @Mock
    private MailSendHistoryWriter historyWriter;

    @Test
    void disabledMailSkipsCredentialAndDeliveryWithoutException() {
        SmtpNotificationMailService service = service(false);
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        service.send(message);

        verify(credentialResolver, never()).resolve();
        verifyNoInteractions(templateEngine, smtpDelivery, fakeDelivery);
        ArgumentCaptor<MailSendHistoryEvent> historyCaptor = ArgumentCaptor.forClass(MailSendHistoryEvent.class);
        verify(historyWriter).record(historyCaptor.capture());
        MailSendHistoryEvent history = historyCaptor.getValue();
        assertEquals("codex@example.invalid", history.recipient());
        assertEquals("subject", history.subject());
        assertEquals(MailSendHistoryStatus.SKIPPED, history.status());
        assertEquals("mail_disabled", history.failureCode());
    }

    @Test
    void templateRenderingFailureIsWrappedAndPropagated() {
        when(configValues.enabled()).thenReturn(true);
        SmtpNotificationMailService service = service();
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .templateCode("mail/missing-template")
                .variables(Map.of("code", "123456"))
                .build();
        when(templateEngine.process(org.mockito.Mockito.eq("mail/missing-template"), org.mockito.Mockito.any(Context.class)))
                .thenThrow(new IllegalStateException("template missing"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.send(message));

        assertEquals("Template rendering failed", thrown.getMessage());
        verify(credentialResolver, never()).resolve();
        verifyNoInteractions(smtpDelivery, fakeDelivery, historyWriter);
    }

    @Test
    void fakeDeliveryReceivesRenderedMailAndCredential() {
        when(configValues.enabled()).thenReturn(true);
        when(configValues.from()).thenReturn("noreply@example.invalid");
        when(configValues.deliveryMode()).thenReturn(NotificationMailProperties.DeliveryMode.FAKE);
        MailCredential credential = new MailCredential(
                UUID.fromString("de0a6fd9-f07d-4201-bf92-279b6c9f099d"),
                "mail@example.invalid",
                "secret");
        when(credentialResolver.resolve()).thenReturn(Optional.of(credential));
        SmtpNotificationMailService service = service();
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .html(true)
                .build();

        service.send(message);

        ArgumentCaptor<RenderedMail> mailCaptor = ArgumentCaptor.forClass(RenderedMail.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<MailCredential>> credentialCaptor = ArgumentCaptor.forClass(Optional.class);
        verify(fakeDelivery).deliver(mailCaptor.capture(), credentialCaptor.capture());
        RenderedMail renderedMail = mailCaptor.getValue();
        assertEquals("noreply@example.invalid", renderedMail.from());
        assertEquals("codex@example.invalid", renderedMail.to());
        assertEquals("subject", renderedMail.subject());
        assertEquals("content", renderedMail.content());
        org.assertj.core.api.Assertions.assertThat(renderedMail.html()).isTrue();
        assertEquals(Optional.of(credential), credentialCaptor.getValue());
        verifyNoInteractions(smtpDelivery);
        ArgumentCaptor<MailSendHistoryEvent> historyCaptor = ArgumentCaptor.forClass(MailSendHistoryEvent.class);
        verify(historyWriter).record(historyCaptor.capture());
        MailSendHistoryEvent history = historyCaptor.getValue();
        assertEquals(MailSendHistoryStatus.SENT, history.status());
        assertEquals(NotificationMailProperties.DeliveryMode.FAKE, history.deliveryMode());
        assertEquals(credential.id(), history.credentialId());
    }

    @Test
    void sinkDeliveryModeUsesFakeDelivery() {
        when(configValues.enabled()).thenReturn(true);
        when(configValues.from()).thenReturn(NotificationMailConfigValues.DEFAULT_FROM);
        when(configValues.deliveryMode()).thenReturn(NotificationMailProperties.DeliveryMode.SINK);
        when(credentialResolver.resolve()).thenReturn(Optional.empty());
        SmtpNotificationMailService service = service();
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        service.send(message);

        verify(fakeDelivery).deliver(org.mockito.Mockito.any(RenderedMail.class), org.mockito.Mockito.eq(Optional.empty()));
        verifyNoInteractions(smtpDelivery);
        ArgumentCaptor<MailSendHistoryEvent> historyCaptor = ArgumentCaptor.forClass(MailSendHistoryEvent.class);
        verify(historyWriter).record(historyCaptor.capture());
        MailSendHistoryEvent history = historyCaptor.getValue();
        assertEquals(MailSendHistoryStatus.SENT, history.status());
        assertEquals(NotificationMailProperties.DeliveryMode.SINK, history.deliveryMode());
    }

    @Test
    void fakeFailureRecordsFailedHistoryAndRethrows() {
        when(configValues.enabled()).thenReturn(true);
        when(configValues.from()).thenReturn(NotificationMailConfigValues.DEFAULT_FROM);
        when(configValues.deliveryMode()).thenReturn(NotificationMailProperties.DeliveryMode.FAKE);
        when(credentialResolver.resolve()).thenReturn(Optional.empty());
        RuntimeException failure = new RuntimeException("Fake mail delivery failed");
        org.mockito.Mockito.doThrow(failure)
                .when(fakeDelivery)
                .deliver(org.mockito.Mockito.any(RenderedMail.class), org.mockito.Mockito.eq(Optional.empty()));
        SmtpNotificationMailService service = service();
        MailMessageDTO message = MailMessageDTO.builder()
                .to("codex@example.invalid")
                .subject("subject")
                .content("content")
                .build();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.send(message, "rabbit-message-02810"));

        assertEquals(failure, thrown);
        ArgumentCaptor<MailSendHistoryEvent> historyCaptor = ArgumentCaptor.forClass(MailSendHistoryEvent.class);
        verify(historyWriter).record(historyCaptor.capture());
        MailSendHistoryEvent history = historyCaptor.getValue();
        assertEquals("rabbit-message-02810", history.correlationId());
        assertEquals(MailSendHistoryStatus.FAILED, history.status());
        assertEquals(NotificationMailProperties.DeliveryMode.FAKE, history.deliveryMode());
        assertEquals(RuntimeException.class.getName(), history.failureCode());
        assertEquals("Fake mail delivery failed", history.failureMessage());
    }

    private SmtpNotificationMailService service(boolean enabled) {
        when(configValues.enabled()).thenReturn(enabled);
        when(configValues.deliveryMode()).thenReturn(NotificationMailProperties.DeliveryMode.SINK);
        return service();
    }

    private SmtpNotificationMailService service() {
        return new SmtpNotificationMailService(
                templateEngine,
                configValues,
                credentialResolver,
                smtpDelivery,
                fakeDelivery,
                historyWriter);
    }
}
