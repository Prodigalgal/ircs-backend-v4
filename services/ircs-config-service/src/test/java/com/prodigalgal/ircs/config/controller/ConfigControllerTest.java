package com.prodigalgal.ircs.config.controller;


import com.prodigalgal.ircs.config.application.ConfigService;
import com.prodigalgal.ircs.config.dto.SystemConfigSummary;
import com.prodigalgal.ircs.config.dto.SystemConfigWriteRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

class ConfigControllerTest {

    private final ConfigService configService = org.mockito.Mockito.mock(ConfigService.class);
    private final ConfigController controller = new ConfigController(configService);

    @Test
    void returnsConfigPage() {
        PageRequest pageable = PageRequest.of(0, 20);
        SystemConfigSummary config = new SystemConfigSummary(
                UUID.randomUUID(),
                "codex.config",
                "enabled",
                "Codex config",
                Instant.parse("2026-06-03T00:00:00Z"),
                false);
        when(configService.listConfigs(pageable, "codex"))
                .thenReturn(new PageImpl<>(List.of(config), pageable, 1));

        var response = controller.listConfigs(pageable, "codex");
        assertEquals(List.of(config), response.content());
        assertEquals(1, response.page().totalElements());
        assertEquals(20, response.page().size());
        assertEquals(0, response.page().number());
        verify(configService).listConfigs(pageable, "codex");
    }

    @Test
    void returnsSingleConfig() {
        SystemConfigSummary config = new SystemConfigSummary(
                UUID.randomUUID(),
                "codex.config",
                "enabled",
                "Codex config",
                Instant.parse("2026-06-03T00:00:00Z"),
                false);
        when(configService.findConfig("codex.config")).thenReturn(Optional.of(config));

        assertEquals(config, controller.getConfig("codex.config").getBody());
        verify(configService).findConfig("codex.config");
    }

    @Test
    void returnsNotFoundForMissingConfig() {
        when(configService.findConfig("missing")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getConfig("missing").getStatusCode());
    }

    @Test
    void createsConfigWithV1LocationShape() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("codex.config", "enabled", "Codex config");
        SystemConfigSummary summary = new SystemConfigSummary(
                UUID.randomUUID(),
                "codex.config",
                "enabled",
                "Codex config",
                Instant.parse("2026-06-03T00:00:00Z"),
                false);
        when(configService.createConfig(request)).thenReturn(summary);

        var response = controller.createConfig(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/configs/codex.config", response.getHeaders().getLocation().toString());
        assertSame(summary, response.getBody());
    }

    @Test
    void updatesConfigWhenFound() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("codex.config", "disabled", "Codex config");
        SystemConfigSummary summary = new SystemConfigSummary(
                UUID.randomUUID(),
                "codex.config",
                "disabled",
                "Codex config",
                Instant.parse("2026-06-03T00:00:00Z"),
                false);
        when(configService.updateConfig("codex.config", request)).thenReturn(Optional.of(summary));

        var response = controller.updateConfig("codex.config", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(summary, response.getBody());
    }

    @Test
    void deletesConfigAsNoContent() {
        var response = controller.deleteConfig("codex.config");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(configService).deleteConfig("codex.config");
    }

    @Test
    void testsConnectionWithDryRunResponse() {
        Map<String, Object> params = Map.of("api_key", "secret");
        Map<String, String> result = Map.of("message", "连接成功", "mode", "dry-run");
        when(configService.testConnection("llm", params)).thenReturn(result);

        var response = controller.testConnection("llm", params);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody());
    }
}
