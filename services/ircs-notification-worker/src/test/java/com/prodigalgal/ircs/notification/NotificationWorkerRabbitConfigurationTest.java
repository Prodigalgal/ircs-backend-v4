package com.prodigalgal.ircs.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

class NotificationWorkerRabbitConfigurationTest {

    @Test
    void messageConverterAllowsNotificationContractPayloads() throws Exception {
        MailMessageDTO expected = MailMessageDTO.builder()
                .to("codex-smoke@example.invalid")
                .subject("subject")
                .content("content")
                .html(false)
                .build();
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_SERIALIZED_OBJECT);
        Message message = new Message(serialize(expected), properties);

        MessageConverter converter = new NotificationWorkerRabbitConfiguration().messageConverter();

        Object actual = converter.fromMessage(message);

        assertThat(actual).isInstanceOf(MailMessageDTO.class);
        MailMessageDTO mail = (MailMessageDTO) actual;
        assertThat(mail.getTo()).isEqualTo(expected.getTo());
        assertThat(mail.getSubject()).isEqualTo(expected.getSubject());
        assertThat(mail.getContent()).isEqualTo(expected.getContent());
        assertThat(mail.isHtml()).isFalse();
    }

    @Test
    void messageConverterAllowsNotificationCommandPayloads() throws Exception {
        NotificationCommand expected = NotificationCommand.builder()
                .commandId("notification-command-1")
                .channel(NotificationChannel.MAIL)
                .recipients(List.of("ops@example.invalid"))
                .subject("subject")
                .content("content")
                .build();
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_SERIALIZED_OBJECT);
        Message message = new Message(serialize(expected), properties);

        MessageConverter converter = new NotificationWorkerRabbitConfiguration().messageConverter();

        Object actual = converter.fromMessage(message);

        assertThat(actual).isInstanceOf(NotificationCommand.class);
        NotificationCommand command = (NotificationCommand) actual;
        assertThat(command.getCommandId()).isEqualTo(expected.getCommandId());
        assertThat(command.getChannel()).isEqualTo(NotificationChannel.MAIL);
        assertThat(command.getRecipients()).containsExactly("ops@example.invalid");
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
