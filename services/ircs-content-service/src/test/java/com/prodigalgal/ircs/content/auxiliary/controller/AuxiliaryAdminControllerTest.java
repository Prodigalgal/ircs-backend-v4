package com.prodigalgal.ircs.content.auxiliary.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.content.auxiliary.application.AuxiliaryAdminService;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.EpisodeResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistCardResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistDetailResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistUpdateRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainResponse;
import com.prodigalgal.ircs.content.video.api.ContentApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuxiliaryAdminControllerTest {

    @Mock
    private AuxiliaryAdminService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new PlaylistAdminController(service),
                        new SourceDomainAdminController(service),
                        new ResolverAdminController(service))
                .setControllerAdvice(new ContentApiExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void playlistRoutesMatchV1AdminContract() throws Exception {
        UUID id = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        PlaylistDetailResponse detail = new PlaylistDetailResponse(
                id,
                "Playlist One",
                videoId,
                "Raw Video",
                List.of(new EpisodeResponse(UUID.randomUUID(), "EP1", "https://cdn.example.test/ep1.m3u8")),
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:00Z"));
        when(service.createPlaylist(any())).thenReturn(detail);
        when(service.updatePlaylist(eq(id), any())).thenReturn(detail);
        when(service.findPlaylists(any(Pageable.class), eq("play"), eq("raw")))
                .thenReturn(new PageImpl<>(
                        List.of(new PlaylistCardResponse(id, "Playlist One", videoId, 1)),
                        PageRequest.of(0, 20),
                        1));
        when(service.findPlaylist(id)).thenReturn(Optional.of(detail));

        mockMvc.perform(post("/api/v1/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Playlist One","videoId":"%s","episodes":[{"name":"EP1","url":"https://cdn.example.test/ep1.m3u8"}]}
                                """.formatted(videoId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/playlists/" + id))
                .andExpect(jsonPath("$.episodes[0].url").value("https://cdn.example.test/ep1.m3u8"));
        mockMvc.perform(put("/api/v1/playlists/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","name":"Playlist One","episodes":[]}
                                """.formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(videoId.toString()));
        mockMvc.perform(get("/api/v1/playlists")
                        .param("page", "0")
                        .param("size", "20")
                        .param("name", "play")
                        .param("videoTitle", "raw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].episodeCount").value(1));
        mockMvc.perform(get("/api/v1/playlists/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(delete("/api/v1/playlists/{id}", id))
                .andExpect(status().isNoContent());

        ArgumentCaptor<PlaylistUpdateRequest> updateCaptor = ArgumentCaptor.forClass(PlaylistUpdateRequest.class);
        verify(service).updatePlaylist(eq(id), updateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(updateCaptor.getValue().id()).isEqualTo(id);
    }

    @Test
    void sourceDomainRoutesMatchV1AdminContract() throws Exception {
        UUID id = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        SourceDomainResponse response = new SourceDomainResponse(
                id,
                "hash",
                "https://cdn.example.test",
                "remark",
                dataSourceId,
                "DS",
                Instant.parse("2026-06-06T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:00Z"));
        when(service.findSourceDomains(any(Pageable.class), eq("cdn"), eq(dataSourceId)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));
        when(service.findSourceDomain(id)).thenReturn(Optional.of(response));
        when(service.updateSourceDomain(eq(id), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/source-domains")
                        .param("keyword", "cdn")
                        .param("dataSourceId", dataSourceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].domainValue").value("https://cdn.example.test"));
        mockMvc.perform(get("/api/v1/source-domains/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSourceName").value("DS"));
        mockMvc.perform(put("/api/v1/source-domains/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","domainValue":"https://cdn.example.test","remark":"remark","dataSourceId":"%s"}
                                """.formatted(id, dataSourceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void resolverRoutesMatchV1AdminContract() throws Exception {
        UUID id = UUID.randomUUID();
        ResolverResponse response = new ResolverResponse(
                id,
                "Resolver",
                true,
                true,
                "remark",
                objectMapper.readTree("[{\"name\":\"line\",\"url\":\"https://resolver.example.test/?url={url}\"}]"));
        when(service.createResolver(any())).thenReturn(response);
        when(service.updateResolver(eq(id), any())).thenReturn(response);
        when(service.findResolvers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));
        when(service.findActiveResolvers()).thenReturn(List.of(response));
        when(service.findResolver(id)).thenReturn(Optional.of(response));

        String body = """
                {"name":"Resolver","isActive":true,"remark":"remark","lines":[{"name":"line","url":"https://resolver.example.test/?url={url}"}]}
                """;
        mockMvc.perform(post("/api/v1/resolvers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/resolvers/" + id))
                .andExpect(jsonPath("$.isActive").value(true));
        mockMvc.perform(put("/api/v1/resolvers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].name").value("line"));
        mockMvc.perform(get("/api/v1/resolvers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Resolver"));
        mockMvc.perform(get("/api/v1/resolvers/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true));
        mockMvc.perform(get("/api/v1/resolvers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(delete("/api/v1/resolvers/{id}", id))
                .andExpect(status().isNoContent());
    }
}
