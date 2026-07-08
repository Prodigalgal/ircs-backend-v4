package com.prodigalgal.ircs.storage.image;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component("r2HealthIndicator")
public class StorageR2HealthIndicator implements HealthIndicator {

    private final R2ObjectStorage r2ObjectStorage;
    private final boolean enabled;
    private final String bucketName;
    private final String publicDomain;

    public StorageR2HealthIndicator(
            R2ObjectStorage r2ObjectStorage,
            @Value("${app.storage.r2.enabled:false}") boolean enabled,
            @Value("${app.storage.r2.bucket-name:}") String bucketName,
            @Value("${app.storage.r2.public-domain:}") String publicDomain) {
        this.r2ObjectStorage = r2ObjectStorage;
        this.enabled = enabled;
        this.bucketName = bucketName;
        this.publicDomain = publicDomain;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up()
                    .withDetail("status", "DISABLED_BY_CONFIG")
                    .withDetail("enabled", false)
                    .build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", true);
        details.put("bucket", displayValue(bucketName));
        details.put("domain", displayValue(publicDomain));

        try {
            if (r2ObjectStorage.isActive()) {
                return Health.up()
                        .withDetails(details)
                        .withDetail("connectivity", "OK")
                        .build();
            }
            return Health.down()
                    .withDetails(details)
                    .withDetail("reason", "R2 storage is enabled but credentials or endpoint are incomplete")
                    .build();
        } catch (RuntimeException ex) {
            return Health.down(ex)
                    .withDetails(details)
                    .build();
        }
    }

    private static String displayValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : "not-configured";
    }
}
