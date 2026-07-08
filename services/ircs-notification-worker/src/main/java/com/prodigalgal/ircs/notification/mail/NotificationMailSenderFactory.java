package com.prodigalgal.ircs.notification.mail;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
class NotificationMailSenderFactory {

    JavaMailSender create(NotificationMailConfigValues.MailConnectOptions options) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(options.host());
        sender.setPort(options.port());
        sender.setUsername(options.username());
        sender.setPassword(options.password());
        sender.setProtocol(options.protocol());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(options.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(options.starttlsEnabled()));
        props.put("mail.smtp.starttls.required", String.valueOf(options.starttlsEnabled()));
        props.put("mail.smtp.ssl.enable", String.valueOf(options.sslEnabled()));

        if (options.sslEnabled()) {
            props.put("mail.smtp.socketFactory.port", String.valueOf(options.port()));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
        }

        props.put("mail.smtp.ssl.trust", "*");
        String timeout = String.valueOf(options.timeout() > 0 ? options.timeout() : 5000);
        props.put("mail.smtp.connectiontimeout", timeout);
        props.put("mail.smtp.timeout", timeout);
        props.put("mail.smtp.writetimeout", timeout);
        if (options.debug()) {
            props.put("mail.debug", "true");
        }
        return sender;
    }
}
