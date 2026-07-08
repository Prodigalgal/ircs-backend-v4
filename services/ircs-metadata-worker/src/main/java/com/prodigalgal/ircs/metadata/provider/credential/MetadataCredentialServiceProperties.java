package com.prodigalgal.ircs.metadata.provider.credential;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.metadata.credential-service")
@Data
public class MetadataCredentialServiceProperties {

    private String baseUrl = "http://localhost:8080";
    private int leaseLimit = 20;
    private String token = "";
    private String serviceId = "metadata-worker";
    private String scopes = "credential:lease";
    private Duration requestTimeout = Duration.ofSeconds(10);
}
