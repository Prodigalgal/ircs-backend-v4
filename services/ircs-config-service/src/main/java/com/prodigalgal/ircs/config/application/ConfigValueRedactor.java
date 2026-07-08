package com.prodigalgal.ircs.config.application;

import com.prodigalgal.ircs.config.domain.SystemConfigRecord;
import com.prodigalgal.ircs.config.dto.SystemConfigSummary;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ConfigValueRedactor {

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(password|passwd|pwd|secret|token|api[-_.]?key|app[-_.]?key|access[-_.]?key|private[-_.]?key|credential|jwt)",
            Pattern.CASE_INSENSITIVE);

    public SystemConfigSummary sanitize(SystemConfigRecord record) {
        return sanitize(record, record.value(), "DB", SystemConfigDefaults.ConfigMetadata.hot());
    }

    public SystemConfigSummary sanitize(SystemConfigRecord record, String effectiveValue, String effectiveSource) {
        return sanitize(record, effectiveValue, effectiveSource, SystemConfigDefaults.ConfigMetadata.hot());
    }

    public SystemConfigSummary sanitize(
            SystemConfigRecord record,
            String effectiveValue,
            String effectiveSource,
            SystemConfigDefaults.ConfigMetadata metadata) {
        boolean sensitive = isSensitive(record.key());
        return new SystemConfigSummary(
                record.id(),
                record.key(),
                sensitive ? null : record.value(),
                sensitive ? null : effectiveValue,
                effectiveSource,
                record.description(),
                record.revision(),
                record.updatedAt(),
                sensitive,
                metadata.activationMode().name(),
                metadata.restartServices());
    }

    public boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY.matcher(normalized).find();
    }
}
