package com.prodigalgal.ircs.identity.application;




import com.prodigalgal.ircs.identity.domain.OAuthProviderRegistry;
import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
import com.prodigalgal.ircs.identity.dto.OAuthProviderSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class OAuthProviderSettingsServiceTest {

    private final OAuthProviderConfigResolver configResolver = mock(OAuthProviderConfigResolver.class);
    private final OAuthProviderSettingsService service = new OAuthProviderSettingsService(configResolver);

    @Test
    void globalDisabledHidesAllProviders() {
        when(configResolver.globalEnabled()).thenReturn(false);

        OAuthProviderSettings settings = service.publicSettings();

        assertFalse(settings.enabled());
        assertTrue(settings.providers().isEmpty());
    }

    @Test
    void providerEnabledFlagControlsPublicEntries() {
        when(configResolver.globalEnabled()).thenReturn(true);
        when(configResolver.usableProviders()).thenReturn(List.of(config("google")));

        OAuthProviderSettings settings = service.publicSettings();

        assertTrue(settings.enabled());
        assertEquals(1, settings.providers().size());
        OAuthProviderSettings.Provider provider = settings.providers().get(0);
        assertEquals("google", provider.code());
        assertEquals("Google", provider.label());
        assertEquals("/api/portal/auth/oauth/google/start", provider.loginUrl());
    }

    @Test
    void allowedProvidersListFiltersEnabledProviders() {
        when(configResolver.globalEnabled()).thenReturn(true);
        when(configResolver.usableProviders()).thenReturn(List.of(config("x"), config("gitee")));

        OAuthProviderSettings settings = service.publicSettings();

        assertEquals(2, settings.providers().size());
        assertEquals("x", settings.providers().get(0).code());
        assertEquals("gitee", settings.providers().get(1).code());
    }

    private static OAuthProviderConfig config(String code) {
        OAuthProviderRegistry.Definition definition = OAuthProviderRegistry.find(code).orElseThrow();
        return new OAuthProviderConfig(
                definition,
                "client-id",
                "client-secret",
                definition.defaultScope(),
                URI.create("https://huawai.mnnu.eu.org" + definition.defaultRedirectPath()),
                "https://huawai.mnnu.eu.org");
    }
}
