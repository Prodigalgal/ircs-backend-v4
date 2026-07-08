package com.prodigalgal.ircs.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.messaging.RabbitTaskHttpStatusException;
import com.prodigalgal.ircs.messaging.TaskSourceTerminalException;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ListScraperClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final ListScraperClient client = new ListScraperClient(
            objectMapper,
            20000,
            ScraperTrafficLimiter.noop(),
            provider(new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport)));

    @Test
    void fetchListUsesSharedOutboundWithHeadersUserAgentAndProxy() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                """
                        {"items":[{"vod_id":"v1","updated":"2026-06-08"}]}
                        """.getBytes(StandardCharsets.UTF_8)));

        var items = client.fetchList(source("https://provider.example.test"), config(
                "CodexUA/1.0",
                false,
                true,
                "proxy.example.test",
                8080,
                "proxy-user",
                "proxy-secret",
                """
                        {"X-Source":"codex","Accept":"application/custom"}
                        """), 3);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().id()).isEqualTo("v1");
        assertThat(transport.requests).hasSize(1);
        OutboundHttpRequest request = transport.requests.getFirst();
        assertThat(request.uri().toString())
                .isEqualTo("https://provider.example.test/api.php?ac=list&pg=3&wd=codex");
        assertThat(request.headers())
                .containsEntry("User-Agent", "CodexUA/1.0")
                .containsEntry("X-Source", "codex")
                .containsEntry("Accept", "application/custom")
                .containsEntry("Accept-Encoding", "gzip, deflate");
        assertThat(request.policy().proxy().enabled()).isTrue();
        assertThat(request.policy().proxy().host()).isEqualTo("proxy.example.test");
        assertThat(request.policy().proxy().port()).isEqualTo(8080);
        assertThat(request.policy().proxy().username()).isEqualTo("proxy-user");
    }

    @Test
    void randomUaUsesControlledBrowserUserAgentPool() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                """
                        {"items":[{"vod_id":"v1","updated":"2026-06-08"}]}
                        """.getBytes(StandardCharsets.UTF_8)));

        client.fetchList(source("https://provider.example.test"), config(
                "should-not-win",
                true,
                false,
                null,
                null,
                null,
                null,
                null), 1);

        String userAgent = transport.requests.getFirst().headers().get("User-Agent");
        assertThat(userAgent)
                .startsWith("Mozilla/5.0")
                .doesNotContain("should-not-win");
    }

    @Test
    void fetchListAddsSourceApiFilterParamsWhenConfigured() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                """
                        {"items":[{"vod_id":"v1","updated":"2026-06-08"}]}
                        """.getBytes(StandardCharsets.UTF_8)));

        client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                "movie",
                48), 3);

        assertThat(transport.requests.getFirst().uri().toString())
                .isEqualTo("https://provider.example.test/api.php?ac=list&pg=3&t=movie&h=48&wd=codex");
    }

    @Test
    void fetchListDetectsGbkResponseBeforeStreamingParse() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                encoded("""
                        {"items":[{"vod_id":"v-gbk","updated":"2026-06-09","name":"中文标题"}]}
                        """, Charset.forName("GB18030"))));

        var items = client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().id()).isEqualTo("v-gbk");
        assertThat(items.getFirst().raw()).containsEntry("name", "中文标题");
    }

    @Test
    void fetchListDetectsBig5ResponseBeforeStreamingParse() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of("Content-Type", List.of("application/json; charset=Big5")),
                encoded("""
                        {"items":[{"vod_id":"v-big5","updated":"2026-06-10","name":"繁體標題"}]}
                        """, Charset.forName("Big5"))));

        var items = client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().id()).isEqualTo("v-big5");
        assertThat(items.getFirst().raw()).containsEntry("name", "繁體標題");
    }

    @Test
    void fetchListDetectsShiftJisResponseBeforeStreamingParse() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of("Content-Type", List.of("application/json; charset=Shift_JIS")),
                encoded("""
                        {"items":[{"vod_id":"v-sjis","updated":"2026-06-10","name":"日本映画"}]}
                        """, Charset.forName("Shift_JIS"))));

        var items = client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().id()).isEqualTo("v-sjis");
        assertThat(items.getFirst().raw()).containsEntry("name", "日本映画");
    }

    @Test
    void fetchListHandlesUtf8BomAndParsesPaginationTotals() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                utf8Bom("""
                        {"total":"88","pagecount":9,"items":[{"vod_id":"v-page","updated":"2026-06-10"}]}
                        """)));

        var page = client.fetchListPage(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 2);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().id()).isEqualTo("v-page");
        assertThat(page.totalPages()).isEqualTo(9);
        assertThat(page.totalItems()).isEqualTo(88);
    }

    @Test
    void fetchDetailDetectsBig5Response() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of("Content-Type", List.of("application/json; charset=\"Big5\"")),
                encoded("""
                        {"list":[{"vod_id":"d-big5","vod_name":"繁體詳情"}]}
                        """, Charset.forName("Big5"))));

        String detail = client.fetchDetail(
                source("https://provider.example.test"),
                config(null, false, false, null, null, null, null, null),
                "d-big5");

        assertThat(detail).contains("繁體詳情");
    }

    @Test
    void fetchListStreamsNestedItemsAndRawValues() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                """
                        {"data":{"items":[{"vod_id":603,"updated":20260609,"tags":["科幻","动作"]}]}}
                        """.getBytes(StandardCharsets.UTF_8)));

        var items = client.fetchList(nestedSource("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().id()).isEqualTo("603");
        assertThat(items.getFirst().updateTime()).isEqualTo("20260609");
        assertThat(items.getFirst().raw()).containsKey("tags");
    }

    @Test
    void fetchListFailsForMalformedJsonResponse() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                "暂不支持搜索".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1))
                .isInstanceOf(TaskSourceTerminalException.class)
                .hasMessageContaining("invalid JSON list response");
    }

    @Test
    void fetchListFailsForInvalidBytes() {
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of(),
                new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02}));

        assertThatThrownBy(() -> client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1))
                .isInstanceOf(TaskSourceTerminalException.class)
                .hasMessageContaining("invalid JSON list response");
    }

    @Test
    void fetchListThrowsHttpStatusSignalForNonSuccessStatus() {
        transport.enqueue(new OutboundHttpResponse(
                404,
                Map.of(),
                "not found".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1))
                .isInstanceOf(RabbitTaskHttpStatusException.class)
                .hasMessageContaining("HTTP status 404");
    }

    @Test
    void fetchListKeepsIoFailureRetryableInsteadOfParseFatal() {
        transport.fail(new IOException("connection reset"));

        assertThatThrownBy(() -> client.fetchList(source("https://provider.example.test"), config(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP fetch failed")
                .hasCauseInstanceOf(IOException.class);
    }


    @Test
    void blocksPrivateDatasourceUrlBeforeTransport() {
        resolver.address = "127.0.0.1";

        assertThatThrownBy(() -> client.fetchDetail(
                source("https://provider.example.test"),
                config(null, false, false, null, null, null, null, null),
                "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP fetch failed");
        assertThat(transport.requests).isEmpty();
    }

    private DataSourceRecord source(String baseUrl) {
        return new DataSourceRecord(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Fake",
                baseUrl,
                "/api.php",
                """
                        {"ac":"list","pg":"{page}"}
                        """,
                "/detail/{id}",
                "{}",
                """
                        {
                          "list_mapping": {
                            "items_path": "$.items",
                            "pagination": {
                              "total_items_path": "$.total",
                              "total_pages_path": "$.pagecount"
                            },
                            "primary_id_path": "$.vod_id",
                            "update_time_path": "$.updated"
                          }
                        }
                        """);
    }

    private DataSourceRecord nestedSource(String baseUrl) {
        return new DataSourceRecord(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Nested",
                baseUrl,
                "/api.php",
                """
                        {"ac":"list","pg":"{page}"}
                        """,
                "/detail/{id}",
                "{}",
                """
                        {
                          "list_mapping": {
                            "items_path": "$.data.items",
                            "pagination": {
                              "total_items_path": "$.data.total",
                              "total_pages_path": "$.data.pagecount"
                            },
                            "primary_id_path": "$.vod_id",
                            "update_time_path": "$.updated"
                          }
                        }
                        """);
    }

    private ManualScrapeConfigRequest config(
            String userAgent,
            boolean enableRandomUa,
            boolean useCustomProxy,
            String proxyHost,
            Integer proxyPort,
            String proxyUsername,
            String proxyPassword,
            String headers) {
        return config(userAgent, enableRandomUa, useCustomProxy, proxyHost, proxyPort, proxyUsername, proxyPassword,
                headers, null, null);
    }

    private ManualScrapeConfigRequest config(
            String userAgent,
            boolean enableRandomUa,
            boolean useCustomProxy,
            String proxyHost,
            Integer proxyPort,
            String proxyUsername,
            String proxyPassword,
            String headers,
            String filterType,
            Integer filterHours) {
        return new ManualScrapeConfigRequest(
                "codex",
                filterType,
                filterHours,
                1,
                1,
                userAgent,
                enableRandomUa,
                useCustomProxy,
                "HTTP",
                proxyHost,
                proxyPort,
                proxyUsername,
                proxyPassword,
                headers,
                0,
                false,
                List.of());
    }

    private byte[] encoded(String value, Charset charset) {
        return value.getBytes(charset);
    }

    private byte[] utf8Bom(String value) {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        private String address = "93.184.216.34";

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            return List.of(InetAddress.getByName(address));
        }
    }

    private static final class FakeTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final Queue<OutboundHttpResponse> responses = new ArrayDeque<>();
        private IOException failure;

        void enqueue(OutboundHttpResponse response) {
            responses.add(response);
        }

        void fail(IOException failure) {
            this.failure = failure;
        }

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return responses.remove();
        }
    }
}
