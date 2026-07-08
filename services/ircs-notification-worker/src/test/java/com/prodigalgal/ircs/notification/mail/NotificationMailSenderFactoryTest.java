package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class NotificationMailSenderFactoryTest {

    @Test
    void createsJavaMailSenderFromRuntimeOptions() {
        NotificationMailConfigValues.MailConnectOptions options =
                new NotificationMailConfigValues.MailConnectOptions(
                        "smtp.example.invalid",
                        2525,
                        "mail@example.invalid",
                        "secret",
                        "smtp",
                        false,
                        true,
                        false,
                        3000,
                        true);

        JavaMailSender sender = new NotificationMailSenderFactory().create(options);

        JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;
        assertEquals("smtp.example.invalid", impl.getHost());
        assertEquals(2525, impl.getPort());
        assertEquals("mail@example.invalid", impl.getUsername());
        assertEquals("secret", impl.getPassword());
        assertEquals("smtp", impl.getProtocol());
        Properties props = impl.getJavaMailProperties();
        assertEquals("false", props.getProperty("mail.smtp.auth"));
        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"));
        assertEquals("false", props.getProperty("mail.smtp.ssl.enable"));
        assertEquals("3000", props.getProperty("mail.smtp.timeout"));
        assertEquals("3000", props.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("3000", props.getProperty("mail.smtp.writetimeout"));
        assertEquals("true", props.getProperty("mail.debug"));
    }
}
