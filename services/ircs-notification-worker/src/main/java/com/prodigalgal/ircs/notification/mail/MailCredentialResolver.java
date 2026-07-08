package com.prodigalgal.ircs.notification.mail;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MailCredentialResolver {

    private final NotificationMailProperties properties;
    private final CredentialServiceMailCredentialRepository repository;
    private final MailSendRateLimiter rateLimiter;

    Optional<MailCredential> resolve() {
        NotificationMailProperties.CredentialProvider provider = properties.getCredentialProvider();
        if (provider == NotificationMailProperties.CredentialProvider.CREDENTIAL_SERVICE) {
            return Optional.of(rateLimiter.selectCredential(repository.leaseCandidates()));
        }
        rateLimiter.awaitGlobalPermit();
        return Optional.empty();
    }
}
