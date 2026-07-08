package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class CoverImageUrlResolverTest {

    private final SystemConfigRepository configRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
    }

    @Test
    void resolvesExternalDomainAndRelativePath() {
        CoverImageRow row = row(CoverImageStorageType.EXTERNAL, "/poster.png", null, "https://img.example.test");

        assertEquals("https://img.example.test/poster.png", newResolver().resolve(row));
    }

    @Test
    void resolvesManagedLocalPathWithDbPublicBase() {
        when(configRepository.findValue("app.storage.public-path")).thenReturn(Optional.of("/assets"));
        CoverImageRow row = row(CoverImageStorageType.LOCAL, "covers/a.png", "covers/a.png", "LOCAL_STORAGE");

        assertEquals("/assets/covers/a.png", newResolver().resolve(row));
    }

    @Test
    void resolvesManagedLocalPathWithV1DefaultPublicBase() {
        when(configRepository.findValue("app.storage.public-path")).thenReturn(Optional.empty());
        CoverImageRow row = row(CoverImageStorageType.LOCAL, "covers/a.png", "covers/a.png", "LOCAL_STORAGE");

        assertEquals("/media/covers/a.png", newResolver().resolve(row));
    }

    @Test
    void resolvesR2PublicDomainFromRuntime() {
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemEnvironment",
                java.util.Map.of("APP_STORAGE_R2_PUBLIC_DOMAIN", "cdn.example.test")));
        CoverImageRow row = row(CoverImageStorageType.R2, "covers/a.png", "covers/a.png", "LOCAL_STORAGE");

        assertEquals("https://cdn.example.test/covers/a.png", newResolver().resolve(row));
    }

    private CoverImageRow row(
            CoverImageStorageType storageType,
            String originalUrl,
            String storagePath,
            String domain) {
        return new CoverImageRow(
                UUID.randomUUID(),
                storageType,
                CoverImageStatus.LOCAL_STORED,
                originalUrl,
                storagePath,
                10L,
                "image/png",
                "hash",
                UUID.randomUUID(),
                domain,
                0,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private CoverImageUrlResolver newResolver() {
        return new CoverImageUrlResolver(new StorageConfigValues(environment, configRepository));
    }
}
