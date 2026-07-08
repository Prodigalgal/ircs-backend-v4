package com.prodigalgal.ircs.magnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class YtsBzMagnetProviderSearchRunnerTest {

    private final MagnetProviderSummary provider = provider();
    private final MagnetExternalIdQuery imdbQuery = new MagnetExternalIdQuery("IMDB", "tt1234567");

    @Test
    void mapsYtsBzJsonToNormalizedCandidates() {
        FakeHttpClient httpClient = FakeHttpClient.ok("""
                {
                  "status": "ok",
                  "data": {
                    "movies": [
                      {
                        "id": 101,
                        "imdb_code": "tt1234567",
                        "title_long": "Codex Movie (2026)",
                        "url": "https://example.invalid/movies/codex",
                        "torrents": [
                          {
                            "hash": "abcdef1234567890abcdef1234567890abcdef12",
                            "quality": "1080p",
                            "type": "web",
                            "video_codec": "x264",
                            "size": "1.4 GB",
                            "size_bytes": 1503238553,
                            "date_uploaded": "2026-06-08 12:34:56",
                            "seeds": 25,
                            "peers": 3
                          },
                          {
                            "hash": "not-a-hash",
                            "quality": "720p"
                          }
                        ]
                      },
                      {
                        "id": 102,
                        "imdb_code": "tt7654321",
                        "title": "Wrong Movie",
                        "torrents": [
                          {
                            "hash": "1111111111111111111111111111111111111111"
                          }
                        ]
                      }
                    ]
                  }
                }
                """);
        YtsBzMagnetProviderSearchRunner runner = runner(httpClient);

        MagnetProviderSearchResult result = runner.search(provider, imdbQuery, UUID.randomUUID());

        assertEquals(200, result.httpStatus());
        assertEquals(
                "https://movies-api.accel.li/api/v2/list_movies.json?query_term=tt1234567&limit=20",
                result.requestUrl());
        assertEquals(1, result.candidates().size());

        MagnetProviderCandidate candidate = result.candidates().get(0);
        assertEquals("ABCDEF1234567890ABCDEF1234567890ABCDEF12", candidate.infoHash());
        assertEquals(
                "magnet:?xt=urn:btih:ABCDEF1234567890ABCDEF1234567890ABCDEF12&dn=Codex%20Movie%20%282026%29%201080p%20web",
                candidate.magnetUri());
        assertEquals("Codex Movie (2026) 1080p web", candidate.title());
        assertEquals(1503238553L, candidate.sizeBytes());
        assertEquals("1.4 GB", candidate.sizeLabel());
        assertEquals(Instant.parse("2026-06-08T12:34:56Z"), candidate.publishedAt());
        assertEquals(25, candidate.seeders());
        assertEquals(3, candidate.leechers());
        assertEquals("1080p", candidate.quality());
        assertEquals("1080p", candidate.resolution());
        assertEquals("IMDB", candidate.matchedExternalIdType());
        assertEquals("tt1234567", candidate.matchedExternalIdValue());
        assertEquals(100, candidate.matchScore());
        assertEquals("https://example.invalid/movies/codex", candidate.sourceUrl());
        assertEquals(List.of("1080p", "web", "x264"), candidate.tags());
        assertEquals("YTS_BZ", candidate.providerEvidence().get("providerType"));
        assertEquals("yts_bz", candidate.providerEvidence().get("providerCode"));
        assertEquals("IMDB", candidate.providerEvidence().get("queryType"));
        assertEquals("tt1234567", candidate.providerEvidence().get("imdbCode"));
        assertTrue(httpClient.requested());
    }

    @Test
    void acceptsTitleQueryAsLowerConfidenceCandidateSearch() {
        FakeHttpClient httpClient = FakeHttpClient.ok("""
                {
                  "status": "ok",
                  "data": {
                    "movies": [
                      {
                        "id": 101,
                        "imdb_code": "tt9999999",
                        "title_long": "Codex Movie (2026)",
                        "url": "https://example.invalid/movies/codex",
                        "torrents": [
                          {
                            "hash": "abcdef1234567890abcdef1234567890abcdef12",
                            "quality": "1080p",
                            "type": "web"
                          }
                        ]
                      }
                    ]
                  }
                }
                """);
        YtsBzMagnetProviderSearchRunner runner = runner(httpClient);

        MagnetProviderSearchResult result = runner.search(
                provider,
                new MagnetExternalIdQuery("TITLE_YEAR", "Codex Movie 2026"),
                UUID.randomUUID());

        assertEquals(1, result.candidates().size());
        MagnetProviderCandidate candidate = result.candidates().getFirst();
        assertEquals("TITLE_YEAR", candidate.matchedExternalIdType());
        assertEquals("Codex Movie 2026", candidate.matchedExternalIdValue());
        assertEquals(82, candidate.matchScore());
        assertEquals("TITLE_YEAR", candidate.providerEvidence().get("queryType"));
        assertEquals("Codex Movie 2026", candidate.providerEvidence().get("queryValue"));
    }

    @Test
    void rejectsNonImdbQueryWithoutHttpRequest() {
        FakeHttpClient httpClient = FakeHttpClient.ok("{}");
        YtsBzMagnetProviderSearchRunner runner = runner(httpClient);

        MagnetProviderRunnerException ex = assertThrows(
                MagnetProviderRunnerException.class,
                () -> runner.search(provider, new MagnetExternalIdQuery("TMDB", "1"), UUID.randomUUID()));

        assertEquals("YTS_BZ_UNSUPPORTED_QUERY", ex.failureType());
        assertNull(ex.requestUrl());
        assertFalseRequest(httpClient);
    }

    @Test
    void classifiesHttpErrorWithoutResponseBodyLeak() {
        FakeHttpClient httpClient = FakeHttpClient.response(503, "secret-upstream-body");
        YtsBzMagnetProviderSearchRunner runner = runner(httpClient);

        MagnetProviderRunnerException ex = assertThrows(
                MagnetProviderRunnerException.class,
                () -> runner.search(provider, imdbQuery, UUID.randomUUID()));

        assertEquals("YTS_BZ_HTTP_ERROR", ex.failureType());
        assertEquals(503, ex.httpStatus());
        assertTrue(ex.requestUrl().contains("query_term=tt1234567"));
        assertTrue(ex.getMessage().contains("YTS_BZ_HTTP_ERROR"));
        assertTrue(!ex.getMessage().contains("secret-upstream-body"));
    }

    @Test
    void classifiesTimeoutWithoutResponseBodyLeak() {
        FakeHttpClient httpClient = FakeHttpClient.timeout();
        YtsBzMagnetProviderSearchRunner runner = runner(httpClient);

        MagnetProviderRunnerException ex = assertThrows(
                MagnetProviderRunnerException.class,
                () -> runner.search(provider, imdbQuery, UUID.randomUUID()));

        assertEquals("YTS_BZ_TIMEOUT", ex.failureType());
        assertNull(ex.httpStatus());
        assertTrue(ex.requestUrl().contains("query_term=tt1234567"));
        assertTrue(!ex.getMessage().contains("secret"));
    }

    @Test
    void classifiesParseFailureWithoutResponseBodyLeak() {
        FakeHttpClient httpClient = FakeHttpClient.ok("secret-not-json");
        YtsBzMagnetProviderSearchRunner runner = runner(httpClient);

        MagnetProviderRunnerException ex = assertThrows(
                MagnetProviderRunnerException.class,
                () -> runner.search(provider, imdbQuery, UUID.randomUUID()));

        assertEquals("YTS_BZ_PARSE_FAILURE", ex.failureType());
        assertEquals(200, ex.httpStatus());
        assertTrue(!ex.getMessage().contains("secret-not-json"));
    }

    @Test
    void sharedOutboundAdapterBlocksPrivateProviderUrlBeforeTransport() {
        FakeOutboundResolver resolver = new FakeOutboundResolver("127.0.0.1");
        FakeOutboundTransport transport = new FakeOutboundTransport();
        YtsBzMagnetProviderSearchRunner.YtsBzHttpClient httpClient =
                new YtsBzMagnetProviderSearchRunner.SharedOutboundYtsBzHttpClient(
                        new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport));

        assertThrows(
                OutboundHttpException.class,
                () -> httpClient.get(URI.create("https://movies-api.accel.li/api/v2/list_movies.json"), Duration.ofSeconds(3)));
        assertTrue(transport.requests.isEmpty());
    }

    private void assertFalseRequest(FakeHttpClient httpClient) {
        assertTrue(!httpClient.requested());
    }

    private YtsBzMagnetProviderSearchRunner runner(FakeHttpClient httpClient) {
        return new YtsBzMagnetProviderSearchRunner(
                new ObjectMapper(),
                MagnetProviderTrafficLimiter.noop(),
                httpClientProvider(httpClient));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<YtsBzMagnetProviderSearchRunner.YtsBzHttpClient> httpClientProvider(
            YtsBzMagnetProviderSearchRunner.YtsBzHttpClient httpClient) {
        ObjectProvider<YtsBzMagnetProviderSearchRunner.YtsBzHttpClient> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfUnique()).thenReturn(httpClient);
        return provider;
    }

    private MagnetProviderSummary provider() {
        return new MagnetProviderSummary(
                UUID.randomUUID(),
                "yts_bz",
                "YTS.BZ",
                "YTS_BZ",
                "https://movies-api.accel.li/api/v2",
                true,
                10,
                "HIGH",
                List.of("IMDB"),
                1000,
                3000,
                10000,
                20,
                true,
                "仅用于测试。",
                null,
                null,
                null,
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:00Z"));
    }

    private static class FakeHttpClient implements YtsBzMagnetProviderSearchRunner.YtsBzHttpClient {

        private final int statusCode;
        private final String body;
        private final boolean timeout;
        private boolean requested;

        private FakeHttpClient(int statusCode, String body, boolean timeout) {
            this.statusCode = statusCode;
            this.body = body;
            this.timeout = timeout;
        }

        static FakeHttpClient ok(String body) {
            return response(200, body);
        }

        static FakeHttpClient response(int statusCode, String body) {
            return new FakeHttpClient(statusCode, body, false);
        }

        static FakeHttpClient timeout() {
            return new FakeHttpClient(200, "", true);
        }

        @Override
        public YtsBzMagnetProviderSearchRunner.YtsBzHttpResponse get(URI uri, Duration timeout)
                throws IOException {
            requested = true;
            if (this.timeout) {
                throw new HttpTimeoutException("secret timeout detail");
            }
            return new YtsBzMagnetProviderSearchRunner.YtsBzHttpResponse(statusCode, body);
        }

        boolean requested() {
            return requested;
        }
    }

    private static final class FakeOutboundResolver implements OutboundAddressResolver {

        private final String address;

        private FakeOutboundResolver(String address) {
            this.address = address;
        }

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            return List.of(InetAddress.getByName(address));
        }
    }

    private static final class FakeOutboundTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) {
            requests.add(request);
            return new OutboundHttpResponse(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
