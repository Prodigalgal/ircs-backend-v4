package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpMailDeliveryTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationMailConfigValues configValues;

    @Mock
    private NotificationMailSenderFactory senderFactory;

    @Test
    void smtpFailureIsWrappedAndPropagated() {
        NotificationMailConfigValues.MailConnectOptions options = defaultOptions();
        when(configValues.smtpOptions(Optional.empty())).thenReturn(options);
        when(senderFactory.create(options)).thenReturn(mailSender);
        SmtpMailDelivery delivery = new SmtpMailDelivery(configValues, senderFactory);
        RenderedMail mail = new RenderedMail(
                "",
                "codex@example.invalid",
                "subject",
                "content",
                false);
        when(mailSender.createMimeMessage()).thenThrow(new IllegalStateException("smtp unavailable"));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> delivery.deliver(mail, Optional.empty()));

        assertEquals("SMTP delivery failed", thrown.getMessage());
    }

    @Test
    void passesCredentialResolvedOptionsToSenderFactory() {
        MailCredential credential = new MailCredential(
                UUID.fromString("de0a6fd9-f07d-4201-bf92-279b6c9f099d"),
                "credential@example.invalid",
                "secret");
        Optional<MailCredential> credentialOption = Optional.of(credential);
        NotificationMailConfigValues.MailConnectOptions options = defaultOptions();
        when(configValues.smtpOptions(credentialOption)).thenReturn(options);
        when(senderFactory.create(options)).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(new org.springframework.mail.javamail.JavaMailSenderImpl()
                .createMimeMessage());
        SmtpMailDelivery delivery = new SmtpMailDelivery(configValues, senderFactory);
        RenderedMail mail = new RenderedMail(
                "from@example.invalid",
                "codex@example.invalid",
                "subject",
                "content",
                false);

        delivery.deliver(mail, credentialOption);

        verify(configValues).smtpOptions(credentialOption);
        verify(senderFactory).create(options);
        verify(mailSender).send(org.mockito.Mockito.any(jakarta.mail.internet.MimeMessage.class));
    }

    private NotificationMailConfigValues.MailConnectOptions defaultOptions() {
        return new NotificationMailConfigValues.MailConnectOptions(
                "smtp.example.invalid",
                2525,
                "",
                "",
                "smtp",
                true,
                false,
                false,
                5000,
                false);
    }
}
