package com.prodigalgal.ircs.magnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MagnetControllerTest {

    private final MagnetQueryService magnetQueryService = org.mockito.Mockito.mock(MagnetQueryService.class);
    private final MagnetController controller = new MagnetController(magnetQueryService);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .build();

    @Test
    void returnsProviders() {
        UUID providerId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(providerId, "yts_bz");
        when(magnetQueryService.listProviders()).thenReturn(List.of(provider));

        assertEquals(List.of(provider), controller.listProviders().getBody());
        verify(magnetQueryService).listProviders();
    }

    @Test
    void returnsProviderDetail() {
        UUID providerId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(providerId, "thepiratebay");
        when(magnetQueryService.findProvider(providerId)).thenReturn(Optional.of(provider));

        assertEquals(provider, controller.getProvider(providerId).getBody());
        verify(magnetQueryService).findProvider(providerId);
    }

    @Test
    void returnsNotFoundForMissingProvider() {
        UUID providerId = UUID.randomUUID();
        when(magnetQueryService.findProvider(providerId)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getProvider(providerId).getStatusCode());
    }

    @Test
    void routesProviderCreateContract() throws Exception {
        UUID providerId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(providerId, "codex_provider");
        when(magnetQueryService.createProvider(any())).thenReturn(provider);

        mockMvc.perform(post("/api/v1/magnet-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody(null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/magnet-providers/" + providerId))
                .andExpect(jsonPath("$.id").value(providerId.toString()))
                .andExpect(jsonPath("$.code").value("codex_provider"));

        verify(magnetQueryService).createProvider(any());
    }

    @Test
    void routesProviderUpdateContract() throws Exception {
        UUID providerId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(providerId, "codex_provider_2");
        when(magnetQueryService.updateProvider(eq(providerId), any())).thenReturn(provider);

        mockMvc.perform(put("/api/v1/magnet-providers/{id}", providerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody(providerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(providerId.toString()))
                .andExpect(jsonPath("$.code").value("codex_provider_2"));

        verify(magnetQueryService).updateProvider(eq(providerId), any());
    }

    @Test
    void returnsApprovedLinksForUnifiedVideo() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetLinkSummary link = new MagnetLinkSummary(
                UUID.randomUUID(),
                unifiedVideoId,
                "yts_bz",
                "abcdef123456",
                "magnet:?xt=urn:btih:abcdef123456",
                "Codex Movie",
                1024L,
                "1 KB",
                null,
                10,
                2,
                "WEB",
                "1080p",
                "IMDB",
                "tt1234567",
                100,
                "APPROVED",
                "https://example.invalid/torrent",
                List.of("1080p"),
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
        when(magnetQueryService.findApprovedLinks(unifiedVideoId)).thenReturn(List.of(link));

        assertEquals(List.of(link), controller.getUnifiedMagnets(unifiedVideoId).getBody());
        verify(magnetQueryService).findApprovedLinks(unifiedVideoId);
    }

    @Test
    void routesUnifiedSearchContract() throws Exception {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        MagnetLinkSummary link = new MagnetLinkSummary(
                linkId,
                unifiedVideoId,
                "yts_bz",
                "abcdef1234567890abcdef1234567890abcdef12",
                "magnet:?xt=urn:btih:abcdef1234567890abcdef1234567890abcdef12",
                "Codex Fixture",
                1024L,
                "1 KB",
                Instant.parse("2026-06-07T00:00:00Z"),
                10,
                1,
                "WEB",
                "1080p",
                "IMDB",
                "tt1234567",
                100,
                "APPROVED",
                "fixture://magnet-provider/yts_bz",
                List.of("dev-safe"),
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"));
        MagnetSearchJobSummary job = new MagnetSearchJobSummary(
                jobId,
                unifiedVideoId,
                "ADMIN_MANUAL",
                "PENDING",
                List.of(),
                List.of(Map.of("type", "IMDB", "value", "tt1234567")),
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                List.of(),
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"));
        when(magnetQueryService.enqueueUnifiedSearch(unifiedVideoId)).thenReturn(job);

        mockMvc.perform(post("/api/v1/magnets/search/unified/{id}", unifiedVideoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.unifiedVideoId").value(unifiedVideoId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalCandidates").value(0))
                .andExpect(jsonPath("$.acceptedCount").value(0));

        verify(magnetQueryService).enqueueUnifiedSearch(unifiedVideoId);
    }

    private MagnetProviderSummary provider(UUID providerId, String code) {
        return new MagnetProviderSummary(
                providerId,
                code,
                "Codex Provider",
                "CODEX_PROVIDER",
                "https://example.invalid/api",
                true,
                10,
                "HIGH",
                List.of("IMDB"),
                1000,
                3000,
                10000,
                20,
                true,
                "仅用于 dev smoke。",
                null,
                null,
                null,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
    }

    private String providerBody(UUID id) {
        return """
                {
                  "id": %s,
                  "code": "codex_provider",
                  "name": "Codex Provider",
                  "providerType": "CODEX_PROVIDER",
                  "baseUrl": "https://example.invalid/api",
                  "enabled": true,
                  "priority": 10,
                  "riskLevel": "HIGH",
                  "supportedExternalIds": ["IMDB"],
                  "minDelayMs": 1000,
                  "maxDelayMs": 3000,
                  "timeoutMs": 10000,
                  "resultLimit": 20,
                  "autoApproveAllowed": true,
                  "contentPolicy": "仅用于 dev smoke。"
                }
                """.formatted(id == null ? "null" : "\"" + id + "\"");
    }
}
