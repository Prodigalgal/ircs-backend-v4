package com.prodigalgal.ircs.credential;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.worker.WorkerInstanceIds;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Order(5)
@Slf4j
class RuntimeCredentialInitializer implements ApplicationRunner {

    private static final String DEFAULT_INIT_FILE_PATH = "/etc/ircs/init/credentials.json";
    private static final String CLASSPATH_INIT_FILE_PATH = "presets/credentials.json";
    private static final String OPENAI_DEFAULT_BASE_URL = CredentialProviderCatalog.DEFAULT_OPENAI_BASE_URL;

    private final CredentialService credentialService;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final DistributedLockManager lockManager;
    private final String workerId;
    private final boolean clusterLockEnabled;
    private final Duration clusterLockTtl;
    private final boolean enabled;
    private final String initFilePath;
    private final boolean classpathFallbackEnabled;
    RuntimeCredentialInitializer(
            CredentialService credentialService,
            Environment environment,
            ObjectMapper objectMapper,
            DistributedLockManager lockManager,
            @Value("${spring.application.name:ircs-credential-service}") String applicationName,
            @Value("${app.credential.cluster-lock.worker-id:${APP_CREDENTIAL_CLUSTER_LOCK_WORKER_ID:}}") String configuredWorkerId,
            @Value("${app.credential.cluster-lock.enabled:true}") boolean clusterLockEnabled,
            @Value("${app.credential.cluster-lock.ttl:PT10M}") Duration clusterLockTtl,
            @Value("${app.credential.initializer.enabled:true}") boolean enabled,
            @Value("${app.credential.initializer.file-path:" + DEFAULT_INIT_FILE_PATH + "}") String initFilePath,
            @Value("${app.credential.initializer.classpath-fallback-enabled:true}") boolean classpathFallbackEnabled) {
        this.credentialService = credentialService;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.lockManager = lockManager;
        this.workerId = WorkerInstanceIds.resolve(applicationName, configuredWorkerId);
        this.clusterLockEnabled = clusterLockEnabled;
        this.clusterLockTtl = clusterLockTtl == null || !clusterLockTtl.isPositive()
                ? Duration.ofMinutes(10)
                : clusterLockTtl;
        this.enabled = enabled;
        this.initFilePath = initFilePath;
        this.classpathFallbackEnabled = classpathFallbackEnabled;
    }

    static RuntimeCredentialInitializer forTest(
            CredentialService credentialService,
            Environment environment,
            ObjectMapper objectMapper,
            boolean enabled,
            String initFilePath,
            boolean classpathFallbackEnabled) {
        return new RuntimeCredentialInitializer(
                credentialService,
                environment,
                objectMapper,
                null,
                "ircs-credential-service",
                "local-test",
                false,
                Duration.ofMinutes(10),
                enabled,
                initFilePath,
                classpathFallbackEnabled);
    }

    @Override
    public void run(ApplicationArguments args) {
        InitializationResult result = initialize();
        if (enabled) {
            log.info(
                    "Runtime credential initializer completed: envInserted={}, fileInserted={}, skipped={}",
                    result.envInserted(),
                    result.fileInserted(),
                    result.skipped());
        }
    }

    InitializationResult initialize() {
        if (!enabled) {
            log.info("Runtime credential initializer is disabled.");
            return new InitializationResult(0, 0, 0);
        }
        if (!clusterLockEnabled) {
            return initializeLocked();
        }
        if (lockManager == null) {
            throw new IllegalStateException("credential distributed lock manager is unavailable");
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.MAINTENANCE_RUNNER);
        return lockManager.callWithLock(profile.keyPrefix() + "credential:runtime-initializer", workerId, clusterLockTtl,
                this::initializeLocked).orElseGet(() -> {
                    log.debug("Runtime credential initializer skipped: distributed lock is held by another instance");
                    return new InitializationResult(0, 0, 0);
                });
    }

    private InitializationResult initializeLocked() {
        int envInserted = importEnvCredentials();
        FileImportResult fileResult = importFileCredentials();
        return new InitializationResult(envInserted, fileResult.inserted(), fileResult.skipped());
    }

    private int importEnvCredentials() {
        int inserted = 0;
        inserted += createIfValid(envOpenAiCredential());
        inserted += createIfValid(envTmdbCredential());
        inserted += createIfValid(envMailCredential());
        inserted += createIfValid(envR2Credential());
        return inserted;
    }

    private CredentialWriteRequest envOpenAiCredential() {
        String apiKey = property("SPRING_AI_OPENAI_API_KEY");
        if (!isValidSecret(apiKey)) {
            return null;
        }
        String baseUrl = property("SPRING_AI_OPENAI_BASE_URL");
        return new CredentialWriteRequest(
                "OPENAI",
                "Env: OpenAI Primary",
                Map.of(
                        "api_key", apiKey.trim(),
                        "base_url", isValidSecret(baseUrl) ? baseUrl.trim() : OPENAI_DEFAULT_BASE_URL),
                true,
                100,
                15,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "Initialized from ENV");
    }

    private CredentialWriteRequest envTmdbCredential() {
        String apiKey = property("APP_METADATA_TMDB_API_KEY");
        if (!isValidSecret(apiKey)) {
            return null;
        }
        return new CredentialWriteRequest(
                "TMDB",
                "Env: TMDB Primary",
                Map.of("api_key", apiKey.trim()),
                true,
                100,
                40,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "Initialized from ENV");
    }

    private CredentialWriteRequest envMailCredential() {
        String username = property("APP_MAIL_USERNAME");
        String password = property("APP_MAIL_PASSWORD");
        if (!isValidSecret(username) || !isValidSecret(password)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username.trim());
        payload.put("password", password.trim());
        payload.put("smtp_host", stringProperty("APP_MAIL_HOST", "MAIL_HOST", CredentialProviderCatalog.DEFAULT_MAIL_HOST));
        payload.put("smtp_port", intProperty(CredentialProviderCatalog.DEFAULT_MAIL_PORT, "APP_MAIL_PORT", "MAIL_PORT"));
        payload.put("smtp_protocol", stringProperty(
                "APP_MAIL_PROTOCOL",
                "SPRING_MAIL_PROTOCOL",
                CredentialProviderCatalog.DEFAULT_MAIL_PROTOCOL));
        payload.put("smtp_auth", booleanProperty(CredentialProviderCatalog.DEFAULT_MAIL_AUTH, "APP_MAIL_SMTP_AUTH"));
        payload.put("smtp_ssl_enabled", booleanProperty(
                CredentialProviderCatalog.DEFAULT_MAIL_SSL,
                "APP_MAIL_SSL_ENABLED"));
        payload.put("smtp_starttls_enabled", booleanProperty(
                CredentialProviderCatalog.DEFAULT_MAIL_STARTTLS,
                "APP_MAIL_STARTTLS_ENABLED"));
        payload.put("smtp_timeout_ms", intProperty(CredentialProviderCatalog.DEFAULT_MAIL_TIMEOUT_MS, "APP_MAIL_TIMEOUT"));
        return new CredentialWriteRequest(
                "MAIL",
                "Env: Mail Primary",
                payload,
                true,
                100,
                null,
                null,
                500L,
                0L,
                0L,
                0L,
                "Initialized from ENV");
    }

    private CredentialWriteRequest envR2Credential() {
        String accountId = property("APP_STORAGE_R2_ACCOUNT_ID");
        String accessKey = property("APP_STORAGE_R2_ACCESS_KEY");
        String secretKey = property("APP_STORAGE_R2_SECRET_KEY");
        if (!isValidSecret(accountId) || !isValidSecret(accessKey) || !isValidSecret(secretKey)) {
            return null;
        }
        return new CredentialWriteRequest(
                "R2",
                "Env: R2 Primary",
                Map.of(
                        "account_id", accountId.trim(),
                        "access_key", accessKey.trim(),
                        "secret_key", secretKey.trim()),
                true,
                100,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                "Initialized from ENV");
    }

    private FileImportResult importFileCredentials() {
        CredentialResource resource = resolveCredentialResource();
        if (resource == null) {
            return new FileImportResult(0, 0);
        }
        try (InputStream inputStream = resource.openStream()) {
            List<InitCredentialDto> credentials = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            int inserted = 0;
            int skipped = 0;
            int index = 0;
            for (InitCredentialDto dto : credentials) {
                CredentialWriteRequest request = dto.toRequest(index);
                if (request == null) {
                    skipped++;
                    continue;
                }
                try {
                    if (credentialService.createIfAbsent(request)) {
                        inserted++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException ex) {
                    skipped++;
                    log.warn("Skipped credential initializer entry provider={} name={}: {}",
                            safeProvider(dto.provider()),
                            safeName(dto.name()),
                            ex.getMessage());
                }
                index++;
            }
            log.info(
                    "Imported credential init resource {}: inserted={}, skipped={}",
                    resource.description(),
                    inserted,
                    skipped);
            return new FileImportResult(inserted, skipped);
        } catch (IOException ex) {
            log.warn("Failed to read credential init resource {}: {}", resource.description(), ex.getMessage());
            return new FileImportResult(0, 1);
        }
    }

    private int createIfValid(CredentialWriteRequest request) {
        if (request == null) {
            return 0;
        }
        try {
            return credentialService.createIfAbsent(request) ? 1 : 0;
        } catch (RuntimeException ex) {
            log.warn("Skipped env credential initializer entry provider={} name={}: {}",
                    request.provider(),
                    safeName(request.name()),
                    ex.getMessage());
            return 0;
        }
    }

    private CredentialResource resolveCredentialResource() {
        if (StringUtils.hasText(initFilePath)) {
            Path path = Path.of(initFilePath.trim());
            if (Files.isRegularFile(path)) {
                return new CredentialResource(path.toString(), () -> Files.newInputStream(path));
            }
        }
        if (classpathFallbackEnabled) {
            ClassPathResource resource = new ClassPathResource(CLASSPATH_INIT_FILE_PATH);
            if (resource.exists()) {
                return new CredentialResource("classpath:" + CLASSPATH_INIT_FILE_PATH, resource::getInputStream);
            }
        }
        return null;
    }

    private String property(String key) {
        return environment.getProperty(key);
    }

    private String stringProperty(String key, String alias, String fallback) {
        String value = property(key);
        if (!StringUtils.hasText(value) && StringUtils.hasText(alias)) {
            value = property(alias);
        }
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private int intProperty(int fallback, String... keys) {
        for (String key : keys) {
            String value = property(key);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanProperty(boolean fallback, String... keys) {
        for (String key : keys) {
            String value = property(key);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true", "1", "yes", "y", "on", "enabled" -> true;
                case "false", "0", "no", "n", "off", "disabled" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private boolean isValidSecret(String value) {
        return StringUtils.hasText(value)
                && !"YOUR_TMDB_API_KEY_HERE".equals(value.trim())
                && !"AIzaSy...".equals(value.trim());
    }

    private String safeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toUpperCase() : "<blank>";
    }

    private String safeName(String name) {
        return StringUtils.hasText(name) ? name.trim() : "<blank>";
    }

    record InitializationResult(int envInserted, int fileInserted, int skipped) {
    }

    private record FileImportResult(int inserted, int skipped) {
    }

    private record CredentialResource(String description, ResourceStream stream) {

        InputStream openStream() throws IOException {
            return stream.open();
        }
    }

    @FunctionalInterface
    private interface ResourceStream {
        InputStream open() throws IOException;
    }

    private record InitCredentialDto(
            String provider,
            String name,
            Map<String, Object> payload,
            Integer priority,
            Integer rateLimit,
            String rateLimitUnit,
            Long dayLimit,
            Long monthLimit,
            Long classALimit,
            Long classBLimit,
            String remark) {

        CredentialWriteRequest toRequest(int index) {
            if (!StringUtils.hasText(provider) || payload == null || payload.isEmpty()) {
                return null;
            }
            Integer resolvedRateLimit = rateLimit != null && rateLimit > 0 ? rateLimit : null;
            String resolvedRateLimitUnit = resolvedRateLimit == null
                    ? null
                    : (StringUtils.hasText(rateLimitUnit) ? rateLimitUnit : "MINUTE");
            String resolvedName = StringUtils.hasText(name) ? name.trim() : provider.trim().toUpperCase() + "-Init-" + index;
            return new CredentialWriteRequest(
                    provider,
                    resolvedName,
                    payload,
                    true,
                    priority,
                    resolvedRateLimit,
                    resolvedRateLimitUnit,
                    dayLimit,
                    monthLimit,
                    classALimit,
                    classBLimit,
                    remark);
        }
    }
}
