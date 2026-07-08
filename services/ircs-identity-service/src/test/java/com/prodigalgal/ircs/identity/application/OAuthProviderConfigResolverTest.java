package com.prodigalgal.ircs.identity.application;


import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class OAuthProviderConfigResolverTest {

    private final IdentityConfigService configService = mock(IdentityConfigService.class);
    private final OAuthProviderConfigResolver resolver = new OAuthProviderConfigResolver(configService);

    @Test
    void globalDisabledReturnsNoProviders() {
        when(configService.bool("member.oauth.enabled", false)).thenReturn(false);

        assertTrue(resolver.usableProviders().isEmpty());
        assertTrue(resolver.findUsable("google").isEmpty());
    }

    @Test
    void missingCredentialsHideEnabledProvider() {
        enabledBaseConfig("google");
        when(configService.value("member.oauth.google.client-id", "")).thenReturn("google-client");
        when(configService.value("member.oauth.google.client-secret", "")).thenReturn("");

        assertTrue(resolver.usableProviders().isEmpty());
    }

    @Test
    void resolvesConfiguredProviderWithProdRedirectUri() {
        enabledBaseConfig("google");
        when(configService.value("member.oauth.google.client-id", "")).thenReturn("google-client");
        when(configService.value("member.oauth.google.client-secret", "")).thenReturn("google-secret");
        when(configService.value("member.oauth.google.scope", "openid email profile")).thenReturn("openid email profile");
        when(configService.value("member.oauth.google.redirect-uri", "/api/portal/auth/oauth/google/callback"))
                .thenReturn("/api/portal/auth/oauth/google/callback");
        when(configService.value("app.frontend.url", "https://huawai.mnnu.eu.org"))
                .thenReturn("https://huawai.mnnu.eu.org");
        when(configService.value("member.oauth.redirect-base-url", "https://huawai.mnnu.eu.org"))
                .thenReturn("https://huawai.mnnu.eu.org");

        OAuthProviderConfig config = resolver.findUsable("google").orElseThrow();

        assertEquals("google", config.code());
        assertEquals("https://huawai.mnnu.eu.org/api/portal/auth/oauth/google/callback",
                config.redirectUri().toString());
    }

    private void enabledBaseConfig(String provider) {
        when(configService.bool("member.oauth.enabled", false)).thenReturn(true);
        when(configService.value("member.oauth.allowed-providers", "google,x,github,gitee,wechat,qq"))
                .thenReturn(provider);
        when(configService.bool("member.oauth." + provider + ".enabled", false)).thenReturn(true);
    }
}
