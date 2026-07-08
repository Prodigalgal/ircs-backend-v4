package com.prodigalgal.ircs.identity.application;





import com.prodigalgal.ircs.identity.config.RuntimeInjectedConfig;
import com.prodigalgal.ircs.identity.messaging.SystemConfigChangePublisher;
import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.infrastructure.SystemConfigRepository;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class IdentityConfigService {

    private final SystemConfigRepository repository;
    private final Environment environment;
    private final PasswordEncoder passwordEncoder;
    private final SystemConfigChangePublisher changePublisher;
    private final ConcurrentMap<IdentityConfigKey, String> runtimeOverrides = new ConcurrentHashMap<>();

    public String value(IdentityConfigKey key) {
        return runtimeOverride(key)
                .or(() -> injectedValue(key))
                .or(() -> repository.findValue(key.dbKey())
                        .filter(StringUtils::hasText))
                .orElse(key.fallbackValue());
    }

    private Optional<String> runtimeOverride(IdentityConfigKey key) {
        return Optional.ofNullable(runtimeOverrides.get(key))
                .filter(StringUtils::hasText);
    }

    private Optional<String> injectedValue(IdentityConfigKey key) {
        return RuntimeInjectedConfig.find(environment, key.propertyKey(), key.dbKey())
                .map(injected -> normalizeInjectedValue(key, injected));
    }

    private String normalizeInjectedValue(IdentityConfigKey key, RuntimeInjectedConfig.InjectedValue injected) {
        if (IdentityConfigKey.ADMIN_PASSWORD == key && isV1RawAdminPasswordKey(key, injected.key())
                && !looksLikeEncodedPassword(injected.value())) {
            return passwordEncoder.encode(injected.value());
        }
        return injected.value();
    }

    private boolean isV1RawAdminPasswordKey(IdentityConfigKey key, String injectedKey) {
        return injectedKey.equals(key.dbKey())
                || injectedKey.equals(RuntimeInjectedConfig.toEnvironmentVariableName(key.dbKey()));
    }

    private boolean looksLikeEncodedPassword(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    public String storedValue(IdentityConfigKey key) {
        return repository.findValue(key.dbKey())
                .filter(StringUtils::hasText)
                .orElse(key.fallbackValue());
    }

    public String value(String dbKey, String fallbackValue) {
        return repository.findValue(dbKey)
                .filter(StringUtils::hasText)
                .orElse(fallbackValue);
    }

    public boolean bool(String dbKey, boolean fallbackValue) {
        return Boolean.parseBoolean(value(dbKey, String.valueOf(fallbackValue)));
    }

    public boolean hasInjectedValue(IdentityConfigKey key) {
        return injectedValue(key).isPresent();
    }

    public void updateValue(IdentityConfigKey key, String value) {
        long revision = repository.upsertValue(key.dbKey(), value);
        changePublisher.publish(
                key.dbKey(),
                SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                revision,
                Math.max(0L, revision - 1));
    }

    public void installRuntimeValues(Map<IdentityConfigKey, String> values) {
        values.forEach((key, value) -> {
            if (StringUtils.hasText(value)) {
                runtimeOverrides.put(key, value);
            }
        });
    }

    public boolean bool(IdentityConfigKey key) {
        return Boolean.parseBoolean(value(key));
    }

    public int intValue(IdentityConfigKey key) {
        return parseInt(value(key), Integer.parseInt(key.fallbackValue()));
    }

    public long longValue(IdentityConfigKey key) {
        return parseLong(value(key), Long.parseLong(key.fallbackValue()));
    }

    public Duration durationProperty(String propertyKey, Duration fallback) {
        String raw = environment.getProperty(propertyKey);
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Duration.parse(raw);
        } catch (Exception ignored) {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(raw);
        }
    }

    public int intProperty(String propertyKey, int fallback) {
        return parseInt(environment.getProperty(propertyKey), fallback);
    }

    private int parseInt(String raw, int fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
