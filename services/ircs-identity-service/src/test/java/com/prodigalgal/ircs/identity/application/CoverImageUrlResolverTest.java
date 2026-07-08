package com.prodigalgal.ircs.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoverImageUrlResolverTest {

    @Test
    void resolvesExternalUrlWithSourceDomain() {
        CoverImageUrlResolver resolver = new CoverImageUrlResolver("");

        assertEquals("https://img.example/path/cover.jpg",
                resolver.resolve("EXTERNAL", "/path/cover.jpg", null, "https://img.example/"));
    }

    @Test
    void resolvesR2UrlWithPublicDomain() {
        CoverImageUrlResolver resolver = new CoverImageUrlResolver("r2.example");

        assertEquals("https://r2.example/covers/a.jpg",
                resolver.resolve("R2", null, "/covers/a.jpg", null));
    }
}
