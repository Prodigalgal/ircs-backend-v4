package com.prodigalgal.ircs.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import com.prodigalgal.ircs.contracts.notification.NotificationChannel;
import com.prodigalgal.ircs.contracts.notification.NotificationCommand;
import com.prodigalgal.ircs.notification.mail.NotificationMailService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationCommandDispatcherTest {

    @Test
    void dispatchesMailCommandToEveryRecipient() {
        NotificationMailService mailService = mock(NotificationMailService.class);
        NotificationCommandDispatcher dispatcher = new NotificationCommandDispatcher(List.of(
                new MailNotificationChannelExecutor(mailService)));
        NotificationCommand command = NotificationCommand.builder()
                .commandId("cmd-1")
                .correlationId("incident-1")
                .channel(NotificationChannel.MAIL)
                .recipients(List.of("ops@example.invalid", "admin@example.invalid"))
                .subject("IRCS alert")
                .content("incident opened")
                .html(false)
                .build();

        List<NotificationChannelExecution> executions = dispatcher.dispatch(command);

        assertThat(executions).extracting(NotificationChannelExecution::recipient)
                .containsExactly("ops@example.invalid", "admin@example.invalid");
        verify(mailService).send(mail("ops@example.invalid"), "incident-1");
        verify(mailService).send(mail("admin@example.invalid"), "incident-1");
    }

    @Test
    void ignoresBlankAndNullRecipientsDuringDispatch() {
        NotificationMailService mailService = mock(NotificationMailService.class);
        NotificationCommandDispatcher dispatcher = new NotificationCommandDispatcher(List.of(
                new MailNotificationChannelExecutor(mailService)));
        NotificationCommand command = NotificationCommand.builder()
                .commandId("cmd-1")
                .correlationId("incident-1")
                .channel(NotificationChannel.MAIL)
                .recipients(Arrays.asList(null, " ", "ops@example.invalid"))
                .subject("IRCS alert")
                .content("incident opened")
                .html(false)
                .build();

        List<NotificationChannelExecution> executions = dispatcher.dispatch(command);

        assertThat(executions).extracting(NotificationChannelExecution::recipient)
                .containsExactly("ops@example.invalid");
        verify(mailService).send(mail("ops@example.invalid"), "incident-1");
    }

    @Test
    void rejectsCommandWithoutRecipients() {
        NotificationCommandDispatcher dispatcher = new NotificationCommandDispatcher(List.of());

        assertThatThrownBy(() -> dispatcher.dispatch(NotificationCommand.builder()
                .channel(NotificationChannel.MAIL)
                .content("content")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient");
    }

    private static MailMessageDTO mail(String to) {
        return MailMessageDTO.builder()
                .to(to)
                .subject("IRCS alert")
                .content("incident opened")
                .html(false)
                .build();
    }
}
