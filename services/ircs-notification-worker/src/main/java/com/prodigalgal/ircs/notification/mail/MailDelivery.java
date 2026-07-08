package com.prodigalgal.ircs.notification.mail;

import java.util.Optional;

interface MailDelivery {
    void deliver(RenderedMail mail, Optional<MailCredential> credential);
}
