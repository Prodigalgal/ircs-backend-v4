package com.prodigalgal.ircs.credential;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.credential.internal")
@Data
public class CredentialInternalAccessProperties {

    private String token = "";
    private String requiredScope = "credential:lease";
}
