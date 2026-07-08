package com.prodigalgal.ircs.credential;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class CredentialService {

    private static final int MAX_LEASE_LIMIT = 100;
    private static final String MAIL_PROVIDER = "MAIL";
    private static final String MAIL_USERNAME_KEY = "username";
    private static final String MAIL_PASSWORD_KEY = "password";

    private final CredentialRepository repository;
    private final CredentialSanitizer sanitizer;
    private final CredentialLeaseMapper leaseMapper;
    private final ObjectMapper objectMapper;
    private final CredentialProviderCatalog providerCatalog;
    private final CredentialLeaseCache leaseCache;
    private final CredentialReadModelCache readModelCache;
    public CredentialService(
            CredentialRepository repository,
            CredentialSanitizer sanitizer,
            CredentialLeaseMapper leaseMapper,
            ObjectMapper objectMapper,
            CredentialProviderCatalog providerCatalog,
            CredentialLeaseCache leaseCache,
            CredentialReadModelCache readModelCache) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.leaseMapper = leaseMapper;
        this.objectMapper = objectMapper;
        this.providerCatalog = providerCatalog;
        this.leaseCache = leaseCache;
        this.readModelCache = readModelCache;
    }

    public List<CredentialSummary> list(String provider, Boolean enabled, int limit) {
        String normalizedProvider = normalizeProvider(provider);
        return readModelCache.list(normalizedProvider, enabled, limit, () -> repository
                .findAll(normalizedProvider, enabled, limit).stream()
                .map(sanitizer::toSummary)
                .toList());
    }

    public Optional<CredentialSummary> findById(UUID id) {
        return readModelCache.findById(id, () -> repository.findById(id).map(sanitizer::toSummary));
    }

    public List<CredentialTemplateField> templates(String provider) {
        return providerCatalog.templates(provider);
    }

    @Transactional
    public CredentialSummary create(CredentialWriteRequest request) {
        CredentialDraft draft = toDraft(IrcsUuidGenerators.nextId(), request);
        if (repository.existsByFingerprint(draft.fingerprint())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Credential already exists (Duplicate Key Content)");
        }
        CredentialSummary summary = sanitizer.toSummary(repository.create(draft));
        evictCredentialCaches("create", draft.provider());
        return summary;
    }

    @Transactional
    public boolean createIfAbsent(CredentialWriteRequest request) {
        CredentialDraft draft = toDraft(IrcsUuidGenerators.nextId(), request);
        if (repository.existsByFingerprint(draft.fingerprint())) {
            return false;
        }
        repository.create(draft);
        evictCredentialCaches("createIfAbsent", draft.provider());
        return true;
    }

    @Transactional
    public Optional<CredentialSummary> update(UUID id, CredentialWriteRequest request) {
        CredentialDraft draft = toDraft(id, request);
        if (repository.existsByFingerprintAndIdNot(draft.fingerprint(), id)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "检测到重复的凭证内容 (API Key 已存在于其他条目中)");
        }
        return repository.update(id, draft).map(record -> {
            evictCredentialCaches("update", null);
            return sanitizer.toSummary(record);
        });
    }

    @Transactional
    public boolean delete(UUID id) {
        boolean deleted = repository.deleteById(id) > 0;
        if (deleted) {
            evictCredentialCaches("delete", null);
        }
        return deleted;
    }

    public void refreshPool() {
        evictCredentialCaches("refreshPool", null);
    }

    public List<ProviderCredentialLease> leaseMailCredentials(int limit) {
        int normalizedLimit = normalizeLeaseLimit(limit);
        if (normalizedLimit == 0) {
            return List.of();
        }
        return leaseCache.get(
                new CredentialLeaseCache.Key(MAIL_PROVIDER, MAIL_USERNAME_KEY, normalizedLimit),
                () -> {
                    CredentialLeaseCache.LeaseWindow window = leaseCache.newWindow();
                    return repository.findEnabledProviderCredentials(MAIL_PROVIDER, MAIL_USERNAME_KEY, normalizedLimit)
                            .stream()
                            .map(record -> leaseMapper.toLease(record, window.leasedAt(), window.expiresAt()))
                            .filter(this::hasMailCredentialSecrets)
                            .toList();
                });
    }

    public List<ProviderCredentialLease> leaseProviderCredentials(
            String provider,
            String requiredPayloadKey,
            int limit) {
        String normalizedRequiredPayloadKey = normalizePayloadKey(requiredPayloadKey);
        int normalizedLimit = normalizeLeaseLimit(limit);
        if (normalizedLimit == 0) {
            return List.of();
        }
        String normalizedProvider = normalizeRequiredProvider(provider);
        return leaseCache.get(
                new CredentialLeaseCache.Key(normalizedProvider, normalizedRequiredPayloadKey, normalizedLimit),
                () -> {
                    CredentialLeaseCache.LeaseWindow window = leaseCache.newWindow();
                    return repository.findEnabledProviderCredentials(
                                    normalizedProvider,
                                    normalizedRequiredPayloadKey,
                                    normalizedLimit)
                            .stream()
                            .map(record -> leaseMapper.toLease(record, window.leasedAt(), window.expiresAt()))
                            .filter(lease -> normalizedRequiredPayloadKey == null
                                    || lease.getSecretPayload().containsKey(normalizedRequiredPayloadKey))
                            .toList();
                });
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return provider.trim().toUpperCase();
    }

    private String normalizeRequiredProvider(String provider) {
        String normalized = normalizeProvider(provider);
        if (normalized == null) {
            throw new IllegalArgumentException("provider is required");
        }
        return normalized;
    }

    private String normalizePayloadKey(String payloadKey) {
        if (payloadKey == null || payloadKey.isBlank()) {
            return null;
        }
        return payloadKey.trim();
    }

    private int normalizeLeaseLimit(int limit) {
        return Math.max(0, Math.min(limit, MAX_LEASE_LIMIT));
    }

    private boolean hasMailCredentialSecrets(ProviderCredentialLease lease) {
        return lease.getSecretPayload() != null
                && StringUtils.hasText(lease.getSecretPayload().get(MAIL_USERNAME_KEY))
                && StringUtils.hasText(lease.getSecretPayload().get(MAIL_PASSWORD_KEY));
    }

    private CredentialDraft toDraft(UUID id, CredentialWriteRequest request) {
        providerCatalog.validate(request);
        String provider = providerCatalog.normalizeProvider(request.provider());
        String payloadJson = writePayload(request);
        String fingerprint = sha256(providerCatalog.fingerprintSource(provider, request.payload()));
        return new CredentialDraft(
                id,
                provider,
                request.name(),
                payloadJson,
                fingerprint,
                request.enabled() == null || request.enabled(),
                defaultInteger(request.priority(), 0),
                request.rateLimit(),
                providerCatalog.normalizeRateLimitUnit(request.rateLimitUnit()),
                defaultLong(request.dayLimit()),
                defaultLong(request.monthLimit()),
                defaultLong(request.classALimit()),
                defaultLong(request.classBLimit()),
                request.remark());
    }

    private String writePayload(CredentialWriteRequest request) {
        try {
            return objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credential payload is not valid JSON", e);
        }
    }

    private Integer defaultInteger(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void evictLeaseCache(String reason, String provider) {
        try {
            long evicted = provider == null ? leaseCache.evictAll() : leaseCache.evictProvider(provider);
            log.debug("Evicted credential lease cache after {}: provider={}, evicted={}", reason, provider, evicted);
        } catch (RuntimeException ex) {
            log.warn("Failed to evict credential lease cache after {}", reason, ex);
        }
    }

    private void evictReadModelCache(String reason) {
        try {
            readModelCache.evictAll();
            log.debug("Evicted credential read-model cache after {}", reason);
        } catch (RuntimeException ex) {
            log.warn("Failed to evict credential read-model cache after {}", reason, ex);
        }
    }

    private void evictCredentialCaches(String reason, String provider) {
        evictLeaseCache(reason, provider);
        evictReadModelCache(reason);
    }
}
