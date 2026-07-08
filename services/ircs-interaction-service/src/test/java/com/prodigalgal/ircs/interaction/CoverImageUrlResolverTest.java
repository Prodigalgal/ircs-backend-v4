package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class CoverImageUrlResolverTest {

    private final SystemConfigRepository configRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private MockEnvironment environment;
    private CoverImageUrlResolver resolver;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        resolver = new CoverImageUrlResolver(environment, configRepository);
    }

    @Test
    void resolvesExternalUrlWithSourceDomain() {
        assertEquals(
                "https://img.example.com/poster/a.jpg",
                resolver.resolve("EXTERNAL", "/poster/a.jpg", null, "https://img.example.com/"));
    }

    @Test
    void r2RuntimeConfigOverridesDbValueAndAddsHttpsScheme() {
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemEnvironment",
                java.util.Map.of("APP_STORAGE_R2_PUBLIC_DOMAIN", "cdn.runtime.example")));

        assertEquals(
                "https://cdn.runtime.example/covers/a.jpg",
                resolver.resolve("R2", "https://origin.invalid/a.jpg", "/covers/a.jpg", null));
        verify(configRepository, never()).findValue(eq("app.storage.r2.public-domain"));
    }

    @Test
    void r2FallsBackToDbConfigBeforeDefault() {
        when(configRepository.findValue("app.storage.r2.public-domain"))
                .thenReturn(Optional.of("https://cdn.db.example/root/"));

        assertEquals(
                "https://cdn.db.example/root/covers/a.jpg",
                resolver.resolve("R2", null, "covers/a.jpg", null));
    }

    @Test
    void r2FallsBackToV1DefaultWhenNoRuntimeOrDbConfigExists() {
        when(configRepository.findValue("app.storage.r2.public-domain"))
                .thenReturn(Optional.empty());

        assertEquals(
                "https://img.mnnu.eu.org/covers/a.jpg",
                resolver.resolve("R2", null, "covers/a.jpg", null));
    }

    @Test
    void localManagedCoverUsesStoragePublicPathFromDb() {
        when(configRepository.findValue("app.storage.public-path"))
                .thenReturn(Optional.of("/media"));

        assertEquals(
                "/media/covers/a.jpg",
                resolver.resolve("LOCAL", null, "covers/a.jpg", null));
    }

    @Test
    void localManagedCoverFallsBackToV1DefaultPublicPath() {
        when(configRepository.findValue("app.storage.public-path"))
                .thenReturn(Optional.empty());

        assertEquals(
                "/media/covers/a.jpg",
                resolver.resolve("LOCAL_STORED", null, "/covers/a.jpg", null));
    }
}
