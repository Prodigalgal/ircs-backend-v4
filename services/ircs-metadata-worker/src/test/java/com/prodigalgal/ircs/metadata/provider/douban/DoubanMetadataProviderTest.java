package com.prodigalgal.ircs.metadata.provider.douban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DoubanMetadataProviderTest {

    @Mock
    private DoubanCredentialRepository credentialRepository;

    @Mock
    private DoubanHttpClient httpClient;

    private DoubanMetadataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DoubanMetadataProvider(
                credentialRepository,
                httpClient,
                new ObjectMapper(),
                new DoubanProviderProperties());
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
        when(httpClient.getJson(argThat(this::isSuggestUri), argThat(Optional::isEmpty))).thenReturn(Optional.of("""
                [
                  {
                    "id":"1291843",
                    "title":"黑客帝国",
                    "sub_title":"The Matrix",
                    "year":"1999",
                    "img":"https://img.example.invalid/matrix.jpg"
                  }
                ]
                """));

        Optional<EnrichedMetadataDTO> result = provider.enrich(context);

        assertThat(result).isPresent();
        EnrichedMetadataDTO metadata = result.orElseThrow();
        assertThat(metadata.getDoubanId()).isEqualTo("1291843");
        assertThat(metadata.getTitle()).isEqualTo("黑客帝国");
        assertThat(metadata.getOriginalTitle()).isEqualTo("The Matrix");
        assertThat(metadata.getYear()).isEqualTo("1999");
        assertThat(metadata.getImageSource()).isEqualTo(ProviderType.DOUBAN);
        verify(credentialRepository).findPreferred();
    }

    @Test
    void returnsEmptyWhenSuggestResultDoesNotMatch() {
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .year("1999")
                .build();
        when(credentialRepository.findPreferred()).thenReturn(Optional.empty());
        when(httpClient.getJson(any(), any())).thenReturn(Optional.of("""
                [{"id":"1","title":"Unrelated","sub_title":"Other","year":"1980"}]
                """));

        assertThat(provider.enrich(context)).isEmpty();
    }

    @Test
    void usesOptionalCredentialWhenPresent() {
        DoubanCredential credential = new DoubanCredential(UUID.randomUUID(), "dbcl2=secret", "UA", 1, "MINUTE", 0);
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("黑客帝国")
                .build();
        when(credentialRepository.findPreferred()).thenReturn(Optional.of(credential));
        when(httpClient.getJson(any(), argThat(optional -> optional.isPresent()
                && optional.orElseThrow().cookie().equals("dbcl2=secret"))))
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

    private boolean isSuggestUri(URI uri) {
        return uri != null
                && uri.toString().startsWith("https://movie.douban.com/j/subject_suggest")
                && uri.toString().contains("q=The%20Matrix");
    }
}
