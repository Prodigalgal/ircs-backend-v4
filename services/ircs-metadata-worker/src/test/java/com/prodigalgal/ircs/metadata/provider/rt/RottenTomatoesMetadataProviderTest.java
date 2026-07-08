package com.prodigalgal.ircs.metadata.provider.rt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RottenTomatoesMetadataProviderTest {

    @Mock
    private RottenTomatoesCredentialRepository credentialRepository;

    @Mock
    private RottenTomatoesHttpClient httpClient;

    private RottenTomatoesMetadataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RottenTomatoesMetadataProvider(
                credentialRepository,
                httpClient,
                new RottenTomatoesProviderProperties());
    }

    @Test
    void enrichesMatchingTitleWithoutCredential() {
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .categorySlug("movie")
                .year("1999")
                .build();
        when(credentialRepository.findPreferred()).thenReturn(Optional.empty());
        when(httpClient.getHtml(argThat(this::isSearchUri), argThat(Optional::isEmpty))).thenReturn(Optional.of("""
                <html><body>
                  <search-page-media-row>
                    <a slot="title" href="/m/matrix_1999">The Matrix</a>
                  </search-page-media-row>
                </body></html>
                """));

        var result = provider.enrich(context);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getRottenTomatoesId()).isEqualTo("matrix_1999");
        verify(credentialRepository).findPreferred();
    }

    @Test
    void fallsBackToLegacyListSelector() {
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .build();
        when(credentialRepository.findPreferred()).thenReturn(Optional.empty());
        when(httpClient.getHtml(any(), any())).thenReturn(Optional.of("""
                <ul slot="list">
                  <li><a href="https://www.rottentomatoes.com/m/the_matrix">The Matrix</a></li>
                </ul>
                """));

        assertThat(provider.enrich(context).orElseThrow().getRottenTomatoesId()).isEqualTo("the_matrix");
    }

    @Test
    void returnsEmptyWhenResultDoesNotMatch() {
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .build();
        when(credentialRepository.findPreferred()).thenReturn(Optional.empty());
        when(httpClient.getHtml(any(), any())).thenReturn(Optional.of("""
                <search-page-media-row><a slot="title" href="/m/unrelated">Unrelated</a></search-page-media-row>
                """));

        assertThat(provider.enrich(context)).isEmpty();
    }

    @Test
    void usesOptionalCredentialWhenPresent() {
        RottenTomatoesCredential credential = new RottenTomatoesCredential(
                UUID.randomUUID(),
                "rt=secret",
                "UA",
                1,
                "MINUTE",
                0);
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .build();
        when(credentialRepository.findPreferred()).thenReturn(Optional.of(credential));
        when(httpClient.getHtml(any(), argThat(optional -> optional.isPresent()
                && optional.orElseThrow().cookie().equals("rt=secret"))))
                .thenReturn(Optional.empty());

        assertThat(provider.enrich(context)).isEmpty();
    }

    @Test
    void doesNotSupportBlankTitle() {
        assertFalse(provider.supports(MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("")
                .build()));
    }

    private boolean isSearchUri(URI uri) {
        return uri != null
                && uri.toString().startsWith("https://www.rottentomatoes.com/search")
                && uri.toString().contains("search=The%20Matrix");
    }
}
