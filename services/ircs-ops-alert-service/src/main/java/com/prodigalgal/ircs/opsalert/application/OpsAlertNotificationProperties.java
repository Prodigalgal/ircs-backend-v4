package com.prodigalgal.ircs.opsalert.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ops-alert.notification.mail")
public record OpsAlertNotificationProperties(
        boolean enabled,
        List<String> recipients,
        String subjectPrefix) {

    public OpsAlertNotificationProperties {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        subjectPrefix = subjectPrefix == null ? "[IRCS 运维告警]" : subjectPrefix;
    }
}
