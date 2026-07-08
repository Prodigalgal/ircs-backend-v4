package com.prodigalgal.ircs.notification.mail;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmtpNotificationMailService implements NotificationMailService {

    private final TemplateEngine templateEngine;
    private final NotificationMailConfigValues configValues;
    private final MailCredentialResolver credentialResolver;
    private final SmtpMailDelivery smtpDelivery;
    private final FakeMailDelivery fakeDelivery;
    private final MailSendHistoryWriter historyWriter;

    @Override
    public void send(MailMessageDTO message) {
        send(message, null);
    }

    @Override
    public void send(MailMessageDTO message, String correlationId) {
        NotificationMailProperties.DeliveryMode mode = configValues.deliveryMode();
        if (!configValues.enabled()) {
            log.warn("Mail service disabled. Skip sending to {}", message.getTo());
            historyWriter.record(MailSendHistoryEvent.skipped(
                    correlationId,
                    message.getTo(),
                    message.getSubject(),
                    message.getTemplateCode(),
                    mode,
                    "mail_disabled"));
            return;
        }

        String content = renderContent(message);
        RenderedMail renderedMail = new RenderedMail(
                configValues.from(),
                message.getTo(),
                message.getSubject(),
                content,
                message.isHtml());
        Optional<MailCredential> credential = Optional.empty();
        try {
            credential = credentialResolver.resolve();
            delivery(mode).deliver(renderedMail, credential);
            historyWriter.record(MailSendHistoryEvent.sent(
                    correlationId,
                    renderedMail,
                    message.getTemplateCode(),
                    mode,
                    credential.orElse(null)));
        } catch (RuntimeException ex) {
            historyWriter.record(MailSendHistoryEvent.failed(
                    correlationId,
                    renderedMail,
                    message.getTemplateCode(),
                    mode,
                    credential.orElse(null),
                    ex));
            throw ex;
        }
    }

    private String renderContent(MailMessageDTO message) {
        if (org.springframework.util.StringUtils.hasText(message.getTemplateCode())) {
            Context context = new Context();
            if (message.getVariables() != null) {
                context.setVariables(message.getVariables());
            }
            try {
                return templateEngine.process(message.getTemplateCode(), context);
            } catch (Exception ex) {
                throw new RuntimeException("Template rendering failed", ex);
            }
        }
        return message.getContent();
    }

    private MailDelivery delivery(NotificationMailProperties.DeliveryMode mode) {
        if (mode == NotificationMailProperties.DeliveryMode.FAKE
                || mode == NotificationMailProperties.DeliveryMode.SINK) {
            return fakeDelivery;
        }
        return smtpDelivery;
    }
}
