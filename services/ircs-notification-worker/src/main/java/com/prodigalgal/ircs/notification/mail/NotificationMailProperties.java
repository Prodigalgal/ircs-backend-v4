package com.prodigalgal.ircs.notification.mail;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notification.mail")
@Data
public class NotificationMailProperties {

    private boolean enabled = true;
    private String from = "";
    private DeliveryMode deliveryMode = DeliveryMode.SMTP;
    private CredentialProvider credentialProvider = CredentialProvider.STATIC;
    private boolean fakeFailureEnabled = false;
    private CredentialServiceProperties credentialService = new CredentialServiceProperties();

    public enum DeliveryMode {
        SMTP,
        FAKE,
        SINK
    }

    public enum CredentialProvider {
        STATIC,
        CREDENTIAL_SERVICE
    }

    @Data
    public static class CredentialServiceProperties {
        private String baseUrl = "http://localhost:8080";
        private int leaseLimit = 20;
        private String token = "";
        private String serviceId = "notification-worker";
        private String scopes = "credential:lease";
        private Duration requestTimeout = Duration.ofSeconds(10);
    }
}
