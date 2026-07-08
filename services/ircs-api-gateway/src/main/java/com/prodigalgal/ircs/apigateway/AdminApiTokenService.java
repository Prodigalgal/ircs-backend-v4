package com.prodigalgal.ircs.apigateway;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerator;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.common.security.IrcsAuthException;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class AdminApiTokenService {

    static final String TOKEN_PREFIX = "ircs_pat_";
    static final String HEADER_API_TOKEN = "X-IRCS-API-Token";

    private static final int TOKEN_RANDOM_BYTES = 32;
    private static final int DISPLAY_PREFIX_LENGTH = 24;
    private static final int MAX_GENERATE_ATTEMPTS = 5;
    private static final IrcsUuidGenerator ID_GENERATOR = IrcsUuidGenerators.defaultGenerator();

    private final AdminApiTokenRepository repository;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration touchInterval;
    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    AdminApiTokenService(
            AdminApiTokenRepository repository,
            @Qualifier("apiGatewayClock") Clock clock,
            ObjectProvider<SecureRandom> secureRandomProvider,
            @Value("${app.gateway.api-token.cache-ttl:PT30S}") String cacheTtl,
            @Value("${app.gateway.api-token.touch-interval:PT5M}") String touchInterval) {
        this.repository = repository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.cacheTtl = positiveOr(parseDuration(cacheTtl, Duration.ofSeconds(30)), Duration.ofSeconds(30));
        this.touchInterval = positiveOr(parseDuration(touchInterval, Duration.ofMinutes(5)), Duration.ofMinutes(5));
        SecureRandom providedRandom = secureRandomProvider == null ? null : secureRandomProvider.getIfAvailable();
        this.secureRandom = providedRandom == null ? new SecureRandom() : providedRandom;
    }

    List<AdminApiTokenDtos.Summary> list() {
        return repository.list().stream()
                .map(this::summary)
                .toList();
    }

    AdminApiTokenDtos.CreatedResponse create(String name, String createdBy) {
        String safeName = normalizeName(name);
        String safeCreatedBy = normalizeActor(createdBy);
        for (int attempt = 0; attempt < MAX_GENERATE_ATTEMPTS; attempt++) {
            String token = generateRawToken();
            String tokenHash = hash(token);
            String tokenPrefix = token.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, token.length()));
            Instant now = clock.instant();
            AdminApiTokenRecord record = new AdminApiTokenRecord(
                    ID_GENERATOR.nextId(),
                    safeName,
                    tokenPrefix,
                    tokenHash,
                    "ACTIVE",
                    safeCreatedBy,
                    now,
                    null,
                    null,
                    null,
                    null);
            try {
                repository.insert(record);
                return new AdminApiTokenDtos.CreatedResponse(
                        record.id(),
                        record.name(),
                        record.tokenPrefix(),
                        token,
                        record.createdAt());
            } catch (DuplicateKeyException ignored) {
                // Extremely unlikely; retry with a fresh random token.
            }
        }
        throw new IllegalStateException("API token generation collision");
    }

    void revoke(UUID id, String revokedBy) {
        if (id == null) {
            throw new IllegalArgumentException("Token id is required");
        }
        repository.revoke(id, normalizeActor(revokedBy), clock.instant());
        cache.clear();
    }

    Optional<IrcsRequestPrincipal> authenticateIfPresent(HttpServletRequest request) {
        Optional<String> token = resolveApiToken(request);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(authenticate(token.get()));
    }

    private IrcsRequestPrincipal authenticate(String rawToken) {
        if (!rawToken.startsWith(TOKEN_PREFIX)) {
            throw invalidToken();
        }
        String tokenHash = hash(rawToken);
        Instant now = clock.instant();
        CachedToken cached = cache.get(tokenHash);
        if (cached != null && cached.validUntil().isAfter(now)) {
            touchIfNeeded(tokenHash, cached, now);
            return cached.principal();
        }
        AdminApiTokenRecord record = repository.findActiveByHash(tokenHash, now)
                .orElseThrow(AdminApiTokenService::invalidToken);
        IrcsRequestPrincipal principal = adminPrincipal(record.tokenPrefix());
        CachedToken refreshed = new CachedToken(record.id(), principal, now.plus(cacheTtl), record.lastUsedAt());
        cache.put(tokenHash, refreshed);
        touchIfNeeded(tokenHash, refreshed, now);
        return principal;
    }

    private void touchIfNeeded(String tokenHash, CachedToken cached, Instant now) {
        Instant lastTouchedAt = cached.lastTouchedAt();
        if (lastTouchedAt != null && lastTouchedAt.plus(touchInterval).isAfter(now)) {
            return;
        }
        CachedToken updated = cached.withLastTouchedAt(now);
        if (cache.replace(tokenHash, cached, updated)) {
            repository.touch(cached.id(), now);
        }
    }

    private Optional<String> resolveApiToken(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String headerToken = normalize(request.getHeader(HEADER_API_TOKEN));
        if (headerToken != null) {
            return Optional.of(headerToken);
        }
        if (shouldResolveQueryToken(request)) {
            String queryToken = normalize(request.getParameter("token"));
            if (queryToken != null && queryToken.startsWith(TOKEN_PREFIX)) {
                return Optional.of(queryToken);
            }
        }
        String authorization = normalize(request.getHeader("Authorization"));
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String bearer = normalize(authorization.substring(7));
        if (bearer != null && bearer.startsWith(TOKEN_PREFIX)) {
            return Optional.of(bearer);
        }
        return Optional.empty();
    }

    private static boolean shouldResolveQueryToken(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
            return true;
        }
        String path = request.getRequestURI();
        return path != null && (path.endsWith("/stream") || path.contains("/stream/"));
    }

    private AdminApiTokenDtos.Summary summary(AdminApiTokenRecord record) {
        return new AdminApiTokenDtos.Summary(
                record.id(),
                record.name(),
                record.tokenPrefix(),
                record.status(),
                record.createdBy(),
                record.createdAt(),
                record.lastUsedAt(),
                record.revokedAt(),
                record.revokedBy(),
                record.expiresAt());
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static IrcsRequestPrincipal adminPrincipal(String tokenPrefix) {
        return new IrcsRequestPrincipal(
                "api-token:" + tokenPrefix,
                IrcsPermissions.ROLE_ADMIN,
                Set.of(IrcsPermissions.ALL),
                IrcsPermissions.defaultScopes(IrcsPermissions.ROLE_ADMIN),
                IrcsPermissions.defaultCategoryScope(IrcsPermissions.ROLE_ADMIN),
                IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ADMIN),
                IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ADMIN),
                IrcsPermissions.defaultContentVisibility(IrcsPermissions.ROLE_ADMIN));
    }

    private static IrcsAuthException invalidToken() {
        return new IrcsAuthException(IrcsAuthException.Reason.INVALID, "API Token 无效或已撤销");
    }

    private static String normalizeName(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Token name is required");
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private static String normalizeActor(String value) {
        String normalized = normalize(value);
        return normalized == null ? "admin" : normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration parseDuration(String value, Duration fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : Duration.parse(normalized);
    }

    private record CachedToken(
            UUID id,
            IrcsRequestPrincipal principal,
            Instant validUntil,
            Instant lastTouchedAt) {

        CachedToken withLastTouchedAt(Instant value) {
            return new CachedToken(id, principal, validUntil, value);
        }
    }
}
