package com.prodigalgal.ircs.config.application;

import com.prodigalgal.ircs.config.domain.SystemConfigRecord;
import com.prodigalgal.ircs.config.dto.SystemConfigSummary;
import com.prodigalgal.ircs.config.dto.SystemConfigWriteRequest;
import com.prodigalgal.ircs.config.config.RuntimeInjectedConfig;
import com.prodigalgal.ircs.config.infrastructure.JdbcConfigRepository;
import com.prodigalgal.ircs.config.infrastructure.SystemConfigChangePublisher;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final JdbcConfigRepository configRepository;
    private final ConfigValueRedactor valueRedactor;
    private final ConfigConnectivityService connectivityService;
    private final SystemConfigDefaults defaults;
    private final Environment environment;
    private final SystemConfigChangePublisher changePublisher;

    public Page<SystemConfigSummary> listConfigs(Pageable pageable, String keyword) {
        return configRepository.findAll(pageable, keyword).map(this::summarize);
    }

    public Optional<SystemConfigSummary> findConfig(String key) {
        return configRepository.findByKey(key).map(this::summarize);
    }

    @Transactional
    public SystemConfigSummary createConfig(SystemConfigWriteRequest request) {
        validateConfigRules(request.key(), request.value());
        if (configRepository.existsByKey(request.key())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config with this key already exists");
        }
        SystemConfigRecord record = configRepository.create(request);
        SystemConfigSummary summary = summarize(record);
        publishChange(record.key(), SystemConfigChangedEvent.Action.CREATED, summary.effectiveSource(), record.revision(), 0L);
        return summary;
    }

    @Transactional
    public Optional<SystemConfigSummary> updateConfig(String key, SystemConfigWriteRequest request) {
        if (!Objects.equals(key, request.key())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key");
        }
        validateConfigRules(request.key(), request.value());
        return configRepository.update(key, request).map(record -> {
            SystemConfigSummary summary = summarize(record);
            publishChange(
                    record.key(),
                    SystemConfigChangedEvent.Action.UPDATED,
                    summary.effectiveSource(),
                    record.revision(),
                    previousRevision(record.revision()));
            return summary;
        });
    }

    @Transactional
    public boolean deleteConfig(String key) {
        if (defaults.isCoreKey(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete core config. Please edit it instead.");
        }
        Optional<SystemConfigRecord> existing = configRepository.findByKey(key);
        int deleted = configRepository.deleteByKey(key);
        if (deleted > 0) {
            String effectiveSource = existing.map(this::summarize)
                    .map(SystemConfigSummary::effectiveSource)
                    .orElse("DB");
            long previousRevision = existing.map(SystemConfigRecord::revision).orElse(0L);
            publishChange(key, SystemConfigChangedEvent.Action.DELETED, effectiveSource, previousRevision + 1, previousRevision);
            return true;
        }
        return false;
    }

    public Map<String, String> testConnection(String type, Map<String, Object> params) {
        return connectivityService.testConnection(type, params);
    }

    private void validateConfigRules(String key, String value) {
        if (SystemConfigDefaults.LLM_PROMPT_KEYS.contains(key)) {
            validateLlmPromptTemplate(value);
        }

        if ("app.mail.properties.ssl".equals(key) && "true".equalsIgnoreCase(value)) {
            String startTls = effectiveValue("app.mail.properties.starttls", "false");
            if ("true".equalsIgnoreCase(startTls)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "配置冲突：无法开启 SSL，因为 STARTTLS 已经启用。请先禁用 STARTTLS。");
            }
        }

        if ("app.mail.properties.starttls".equals(key) && "true".equalsIgnoreCase(value)) {
            String ssl = effectiveValue("app.mail.properties.ssl", "false");
            if ("true".equalsIgnoreCase(ssl)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "配置冲突：无法开启 STARTTLS，因为 SSL 已经启用。请先禁用 SSL。");
            }
        }
    }

    private String effectiveValue(String key, String fallback) {
        return configRepository.findValue(key)
                .filter(StringUtils::hasText)
                .or(() -> RuntimeInjectedConfig.find(environment, defaults.injectionKeys(key)))
                .orElse(fallback);
    }

    private SystemConfigSummary summarize(SystemConfigRecord record) {
        EffectiveConfigValue effective = resolveEffectiveValue(record);
        return valueRedactor.sanitize(record, effective.value(), effective.source(), defaults.metadata(record.key()));
    }

    private void publishChange(
            String key,
            SystemConfigChangedEvent.Action action,
            String effectiveSource,
            long revision,
            long previousRevision) {
        changePublisher.publish(key, action, effectiveSource, revision, previousRevision);
    }

    private long previousRevision(long revision) {
        return Math.max(0L, revision - 1);
    }

    private EffectiveConfigValue resolveEffectiveValue(SystemConfigRecord record) {
        if (StringUtils.hasText(record.value())) {
            return new EffectiveConfigValue(record.value(), "DB");
        }
        Optional<String> injectedValue = RuntimeInjectedConfig.find(environment, defaults.injectionKeys(record.key()));
        if (injectedValue.isPresent()) {
            return new EffectiveConfigValue(injectedValue.get(), "INJECTED");
        }
        return defaults.staticDefaultValue(record.key())
                .filter(StringUtils::hasText)
                .map(value -> new EffectiveConfigValue(value, "DEFAULT"))
                .orElseGet(() -> new EffectiveConfigValue(record.value(), "DB"));
    }

    private void validateLlmPromptTemplate(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM Prompt 不能为空。");
        }
        Set<String> required = Set.of("{rawItems}", "{standardItems}");
        boolean missing = required.stream().anyMatch(token -> !value.contains(token));
        if (missing) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                "LLM Prompt 必须同时包含 {rawItems} 与 {standardItems} 占位符。");
        }
    }

    private record EffectiveConfigValue(String value, String source) {
    }
}
