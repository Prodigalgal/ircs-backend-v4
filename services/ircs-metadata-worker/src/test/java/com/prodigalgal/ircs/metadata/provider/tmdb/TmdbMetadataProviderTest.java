package com.prodigalgal.ircs.metadata.provider.tmdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.metadata.EnrichedMetadataDTO;
import com.prodigalgal.ircs.contracts.metadata.MetadataSearchContext;
import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TmdbMetadataProviderTest {

    @Mock
    private TmdbCredentialRepository credentialRepository;

    @Mock
    private TmdbRateLimiter rateLimiter;

    @Mock
    private TmdbHttpClient httpClient;

    private TmdbCredential credential;
    private TmdbMetadataProvider provider;

    @BeforeEach
    void setUp() {
        TmdbProviderProperties properties = new TmdbProviderProperties();
        credential = new TmdbCredential(UUID.randomUUID(), "test-key", 30, "MINUTE", 0);
        provider = new TmdbMetadataProvider(
                credentialRepository,
                rateLimiter,
                httpClient,
                new ObjectMapper(),
                properties);
    }

    @Test
    void enrichesMatchingMovieWithDetails() {
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("The Matrix")
                .categorySlug("movie")
                .year("1999")
                .build();
        when(credentialRepository.findEnabled()).thenReturn(List.of(credential));
        when(httpClient.getJson(argThat(this::isMovieSearchUri))).thenReturn(Optional.of("""
                {"total_pages":1,"results":[
                  {"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31"}
                ]}
                """));
        when(httpClient.getJson(argThat(this::isMovieDetailUri))).thenReturn(Optional.of("""
                {
                  "title":"The Matrix",
                  "original_title":"The Matrix",
                  "overview":"A hacker discovers reality.",
                  "poster_path":"/matrix.jpg",
                  "backdrop_path":"/matrix-bg.jpg",
                  "vote_average":8.2,
                  "release_date":"1999-03-31",
                  "external_ids":{"imdb_id":"tt0133093"},
                  "production_countries":[{"name":"United States"}],
                  "genres":[{"name":"Science Fiction"}],
                  "credits":{
                    "cast":[{"name":"Keanu Reeves"}],
                    "crew":[{"job":"Director","name":"Lana Wachowski"}]
                  }
                }
                """));

        Optional<EnrichedMetadataDTO> result = provider.enrich(context);

        assertTrue(result.isPresent());
        EnrichedMetadataDTO metadata = result.get();
        assertEquals("603", metadata.getTmdbId());
        assertEquals("tt0133093", metadata.getImdbId());
        assertEquals("The Matrix", metadata.getTitle());
        assertEquals("1999", metadata.getYear());
        assertEquals(ProviderType.TMDB, metadata.getImageSource());
        assertTrue(metadata.getActorNames().contains("Keanu Reeves"));
        assertTrue(metadata.getDirectorNames().contains("Lana Wachowski"));
        assertTrue(metadata.getGenreNames().contains("Science Fiction"));
        verify(rateLimiter, times(2)).acquire(credential);
    }

    @Test
    void rotatesStartingCredentialAcrossRequests() {
        TmdbCredential first = new TmdbCredential(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "key-1",
                30,
                "MINUTE",
                0);
        TmdbCredential second = new TmdbCredential(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "key-2",
                30,
                "MINUTE",
                0);
        MetadataSearchContext context = MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("No Match")
                .categorySlug("movie")
                .build();
        when(credentialRepository.findEnabled()).thenReturn(List.of(first, second));
        when(httpClient.getJson(any(URI.class))).thenReturn(Optional.of("""
                {"total_pages":1,"results":[]}
                """));

        provider.enrich(context);
        provider.enrich(context);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(httpClient, times(2)).getJson(uriCaptor.capture());
        assertTrue(uriCaptor.getAllValues().get(0).toString().contains("api_key=key-1"));
        assertTrue(uriCaptor.getAllValues().get(1).toString().contains("api_key=key-2"));
        verify(rateLimiter).acquire(first);
        verify(rateLimiter).acquire(second);
    }

    @Test
    void throwsRetryableFailureWhenNoCredentialsExist() {
        when(credentialRepository.findEnabled()).thenReturn(List.of());

        MetadataProviderRetryableException ex = assertThrows(
                MetadataProviderRetryableException.class,
                () -> provider.enrich(MetadataSearchContext.builder()
                        .videoId(UUID.randomUUID())
                        .title("The Matrix")
                        .build()));

        assertEquals("POOL_EXHAUSTED", ex.getErrorCode());
    }

    @Test
    void doesNotSupportBlankTitle() {
        assertFalse(provider.supports(MetadataSearchContext.builder()
                .videoId(UUID.randomUUID())
                .title("")
                .build()));
    }

    private boolean isMovieSearchUri(URI uri) {
        return uri != null && uri.toString().contains("/search/movie");
    }

    private boolean isMovieDetailUri(URI uri) {
        return uri != null && uri.toString().contains("/movie/603");
    }
}
