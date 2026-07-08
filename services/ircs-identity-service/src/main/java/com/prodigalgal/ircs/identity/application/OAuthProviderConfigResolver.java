package com.prodigalgal.ircs.identity.application;



import com.prodigalgal.ircs.identity.domain.OAuthProviderRegistry;
import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class OAuthProviderConfigResolver {

    private static final String ENABLED_KEY = "member.oauth.enabled";
    private static final String ALLOWED_PROVIDERS_KEY = "member.oauth.allowed-providers";
    private static final String REDIRECT_BASE_URL_KEY = "member.oauth.redirect-base-url";
    private static final String FRONTEND_URL_KEY = "app.frontend.url";
    private static final String DEFAULT_ALLOWED_PROVIDERS = "google,x,github,gitee,wechat,qq";
    private static final String DEFAULT_FRONTEND_BASE_URL = "https://huawai.mnnu.eu.org";

    private final IdentityConfigService configService;

    OAuthProviderConfigResolver(IdentityConfigService configService) {
        this.configService = configService;
    }

    boolean globalEnabled() {
        return configService.bool(ENABLED_KEY, false);
    }

    Optional<OAuthProviderConfig> findUsable(String code) {
        if (!globalEnabled()) {
            return Optional.empty();
        }
        Set<String> allowed = allowedProviders();
        return OAuthProviderRegistry.find(code)
                .filter(definition -> allowed.contains(definition.code()))
                .filter(definition -> configService.bool(enabledKey(definition.code()), false))
                .flatMap(this::resolve);
    }

    List<OAuthProviderConfig> usableProviders() {
        if (!globalEnabled()) {
            return List.of();
        }
        Set<String> allowed = allowedProviders();
        return OAuthProviderRegistry.definitions().stream()
                .filter(definition -> allowed.contains(definition.code()))
                .filter(definition -> configService.bool(enabledKey(definition.code()), false))
                .map(this::resolve)
                .flatMap(Optional::stream)
                .toList();
    }

    String frontendBaseUrl() {
        String fallback = configService.value(FRONTEND_URL_KEY, DEFAULT_FRONTEND_BASE_URL);
        String raw = configService.value(REDIRECT_BASE_URL_KEY, fallback);
        return stripTrailingSlash(StringUtils.hasText(raw) ? raw : fallback);
    }

    private Optional<OAuthProviderConfig> resolve(OAuthProviderRegistry.Definition definition) {
        String clientId = configService.value(definition.clientIdKey(), "");
        String clientSecret = configService.value(definition.clientSecretKey(), "");
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            return Optional.empty();
        }
        String scope = configService.value(definition.scopeKey(), definition.defaultScope());
        String redirectPath = configService.value(definition.redirectUriKey(), definition.defaultRedirectPath());
        return Optional.of(new OAuthProviderConfig(
                definition,
                clientId.trim(),
                clientSecret.trim(),
                StringUtils.hasText(scope) ? scope.trim() : definition.defaultScope(),
                URI.create(frontendBaseUrl() + normalizePath(redirectPath, definition.defaultRedirectPath())),
                frontendBaseUrl()));
    }

    private Set<String> allowedProviders() {
        return Arrays.stream(configService.value(ALLOWED_PROVIDERS_KEY, DEFAULT_ALLOWED_PROVIDERS).split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String enabledKey(String code) {
        return "member.oauth." + code + ".enabled";
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = StringUtils.hasText(value) ? value.trim() : DEFAULT_FRONTEND_BASE_URL;
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String normalizePath(String value, String fallback) {
        String path = StringUtils.hasText(value) ? value.trim() : fallback;
        return path.startsWith("/") ? path : "/" + path;
    }
}
