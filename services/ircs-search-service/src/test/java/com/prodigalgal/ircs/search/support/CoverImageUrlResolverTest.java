package com.prodigalgal.ircs.search.support;

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
    void r2RuntimeConfigOverridesDbValueAndAddsHttpsScheme() {
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemEnvironment",
                java.util.Map.of("APP_STORAGE_R2_PUBLIC_DOMAIN", "cdn.runtime.example")));

        String result = resolver.resolve("R2", null, "/covers/a.jpg", null);

        assertEquals("https://cdn.runtime.example/covers/a.jpg", result);
        verify(configRepository, never()).findValue(eq("app.storage.r2.public-domain"));
    }

    @Test
    void r2FallsBackToDbConfigBeforeDefault() {
        when(configRepository.findValue("app.storage.r2.public-domain"))
                .thenReturn(Optional.of("https://cdn.db.example/root/"));

        String result = resolver.resolve("R2", null, "covers/a.jpg", null);

        assertEquals("https://cdn.db.example/root/covers/a.jpg", result);
    }

    @Test
    void r2FallsBackToV1DefaultWhenNoRuntimeOrDbConfigExists() {
        when(configRepository.findValue("app.storage.r2.public-domain"))
                .thenReturn(Optional.empty());

        String result = resolver.resolve("R2", null, "covers/a.jpg", null);

        assertEquals("https://img.mnnu.eu.org/covers/a.jpg", result);
    }

    @Test
    void localManagedCoverUsesStoragePublicPathFromDb() {
        when(configRepository.findValue("app.storage.public-path"))
                .thenReturn(Optional.of("/media"));

        String result = resolver.resolve("LOCAL", null, "covers/a.jpg", null);

        assertEquals("/media/covers/a.jpg", result);
    }

    @Test
    void localManagedCoverFallsBackToV1DefaultPublicPath() {
        when(configRepository.findValue("app.storage.public-path"))
                .thenReturn(Optional.empty());

        String result = resolver.resolve("LOCAL_STORED", null, "/covers/a.jpg", null);

        assertEquals("/media/covers/a.jpg", result);
    }

    @Test
    void externalCoverKeepsV1SourceDomainJoinBehavior() {
        String result = resolver.resolve("EXTERNAL", "/poster.jpg", null, "https://source.example/");

        assertEquals("https://source.example/poster.jpg", result);
    }
}
