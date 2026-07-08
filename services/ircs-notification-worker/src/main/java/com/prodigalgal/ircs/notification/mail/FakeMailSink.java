package com.prodigalgal.ircs.notification.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class FakeMailSink {

    private final List<SentMail> messages = new CopyOnWriteArrayList<>();

    void record(RenderedMail mail) {
        messages.add(new SentMail(
                mail.from(),
                mail.to(),
                mail.subject(),
                mail.content(),
                mail.html()));
    }

    public List<SentMail> messages() {
        return List.copyOf(messages);
    }

    public void clear() {
        messages.clear();
    }

    public record SentMail(String from, String to, String subject, String content, boolean html) {
    }
}
