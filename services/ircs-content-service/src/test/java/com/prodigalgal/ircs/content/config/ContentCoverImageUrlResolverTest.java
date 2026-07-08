package com.prodigalgal.ircs.content.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ContentCoverImageUrlResolverTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void resolvesLocalManagedCoverThroughStoragePublicPath() {
        when(repository.findValue("app.storage.public-path")).thenReturn(Optional.of("/media"));

        ContentCoverImageUrlResolver resolver = resolver(new MockEnvironment());

        assertEquals(
                "/media/covers/a.webp",
                resolver.resolve("LOCAL", "https://source.example/a.webp", "covers/a.webp", null));
    }

    @Test
    void resolvesR2ManagedCoverThroughPublicDomain() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_STORAGE_R2_PUBLIC_DOMAIN", "cdn.example.test");

        ContentCoverImageUrlResolver resolver = resolver(environment);

        assertEquals(
                "https://cdn.example.test/covers/a.webp",
                resolver.resolve("R2", "https://source.example/a.webp", "/covers/a.webp", null));
        verify(repository, never()).findValue("app.storage.r2.public-domain");
    }

    @Test
    void keepsAbsoluteExternalCoverUrlReadableByBrowser() {
        ContentCoverImageUrlResolver resolver = resolver(new MockEnvironment());

        assertEquals(
                "https://source.example/covers/a.webp",
                resolver.resolve("EXTERNAL", "https://source.example/covers/a.webp", null, "source.example"));
    }

    @Test
    void joinsRelativeExternalCoverWithSourceDomain() {
        ContentCoverImageUrlResolver resolver = resolver(new MockEnvironment());

        assertEquals(
                "https://source.example/covers/a.webp",
                resolver.resolve("EXTERNAL", "/covers/a.webp", null, "https://source.example"));
    }

    @Test
    void returnsNullWhenCoverHasNoUrl() {
        ContentCoverImageUrlResolver resolver = resolver(new MockEnvironment());

        assertNull(resolver.resolve("EXTERNAL", null, null, null));
    }

    private ContentCoverImageUrlResolver resolver(MockEnvironment environment) {
        return new ContentCoverImageUrlResolver(environment, repository);
    }
}
