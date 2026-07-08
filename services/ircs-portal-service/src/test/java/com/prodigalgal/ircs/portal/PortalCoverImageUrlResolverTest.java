package com.prodigalgal.ircs.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class PortalCoverImageUrlResolverTest {

    private final SystemConfigRepository configRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private MockEnvironment environment;
    private PortalCoverImageUrlResolver resolver;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        resolver = new PortalCoverImageUrlResolver(environment, configRepository);
    }

    @Test
    void resolvesExternalUrlWithSourceDomain() {
        assertEquals("https://cdn.example.invalid/covers/a.webp",
                resolver.resolve("EXTERNAL", "/covers/a.webp", null, "https://cdn.example.invalid/"));
    }

    @Test
    void r2RuntimeConfigOverridesDbValueAndAddsHttpsScheme() {
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemEnvironment",
                java.util.Map.of("APP_STORAGE_R2_PUBLIC_DOMAIN", "assets.example.invalid")));

        assertEquals("https://assets.example.invalid/covers/a.webp",
                resolver.resolve("R2", null, "covers/a.webp", null));
        verify(configRepository, never()).findValue(eq("app.storage.r2.public-domain"));
    }

    @Test
    void r2FallsBackToDbConfigBeforeDefault() {
        when(configRepository.findValue("app.storage.r2.public-domain"))
                .thenReturn(Optional.of("https://db-assets.example.invalid/root/"));

        assertEquals("https://db-assets.example.invalid/root/covers/a.webp",
                resolver.resolve("R2", null, "/covers/a.webp", null));
    }

    @Test
    void r2FallsBackToV1DefaultWhenNoRuntimeOrDbConfigExists() {
        when(configRepository.findValue("app.storage.r2.public-domain"))
                .thenReturn(Optional.empty());

        assertEquals("https://img.mnnu.eu.org/covers/a.webp",
                resolver.resolve("R2", null, "covers/a.webp", null));
    }

    @Test
    void localManagedCoverUsesStoragePublicPathFromDb() {
        when(configRepository.findValue("app.storage.public-path"))
                .thenReturn(Optional.of("/media"));

        assertEquals("/media/covers/a.webp",
                resolver.resolve("LOCAL", null, "covers/a.webp", null));
    }

    @Test
    void localManagedCoverFallsBackToV1DefaultPublicPath() {
        when(configRepository.findValue("app.storage.public-path"))
                .thenReturn(Optional.empty());

        assertEquals("/media/covers/a.webp",
                resolver.resolve("LOCAL_STORED", null, "/covers/a.webp", null));
    }

    @Test
    void returnsNullWhenExternalUrlIsMissing() {
        assertNull(resolver.resolve("EXTERNAL", null, null, "https://cdn.example.invalid"));
    }
}
