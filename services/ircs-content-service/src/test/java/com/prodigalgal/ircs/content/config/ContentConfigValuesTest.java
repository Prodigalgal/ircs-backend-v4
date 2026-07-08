package com.prodigalgal.ircs.content.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ContentConfigValuesTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void usesV3FallbackWhenNoRuntimeOrDbConfigExists() {
        ContentConfigValues values = values(new MockEnvironment());

        assertEquals(ContentConfigValues.DEFAULT_SCRAPER_BASE_URL, values.scraperBaseUrl());
    }

    @Test
    void dbCanonicalKeyOverridesV3Fallback() {
        when(repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY))
                .thenReturn(Optional.of("http://scraper-from-db:8080"));

        ContentConfigValues values = values(new MockEnvironment());

        assertEquals("http://scraper-from-db:8080", values.scraperBaseUrl());
    }

    @Test
    void canonicalRuntimeKeyOverridesDbConfig() {
        when(repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY))
                .thenReturn(Optional.of("http://scraper-from-db:8080"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONTENT_INTERNAL_SCRAPER_BASE_URL", "http://scraper-from-env:8080");

        ContentConfigValues values = values(environment);

        assertEquals("http://scraper-from-env:8080", values.scraperBaseUrl());
    }

    @Test
    void existingDevRuntimeAliasOverridesDbConfig() {
        when(repository.findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY))
                .thenReturn(Optional.of("http://scraper-from-db:8080"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONTENT_SCRAPER_BASE_URL", "http://scraper-from-dev-env:8080");

        ContentConfigValues values = values(environment);

        assertEquals("http://scraper-from-dev-env:8080", values.scraperBaseUrl());
    }

    @Test
    void runtimeOverrideSkipsDbFallbackCache() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_CONTENT_INTERNAL_SCRAPER_BASE_URL", "http://scraper-from-env-only:8080");

        ContentConfigValues values = values(environment);

        assertEquals("http://scraper-from-env-only:8080", values.scraperBaseUrl());
        verify(repository, never()).findValue(ContentConfigValues.SCRAPER_BASE_URL_KEY);
    }

    private ContentConfigValues values(MockEnvironment environment) {
        return new ContentConfigValues(environment, repository);
    }
}
