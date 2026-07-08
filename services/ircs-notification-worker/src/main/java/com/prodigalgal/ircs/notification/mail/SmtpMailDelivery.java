package com.prodigalgal.ircs.notification.mail;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
class SmtpMailDelivery implements MailDelivery {

    private final NotificationMailConfigValues configValues;
    private final NotificationMailSenderFactory senderFactory;

    @Override
    public void deliver(RenderedMail mail, Optional<MailCredential> credential) {
        try {
            JavaMailSender mailSender = senderFactory.create(configValues.smtpOptions(credential));
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());

            if (StringUtils.hasText(mail.from())) {
                helper.setFrom(mail.from());
            }
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.content(), mail.html());

            mailSender.send(mimeMessage);
            log.info("Sent email to {}", mail.to());
        } catch (Exception e) {
            throw new RuntimeException("SMTP delivery failed", e);
        }
    }
}
