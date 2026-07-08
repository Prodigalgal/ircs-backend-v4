package com.prodigalgal.ircs.ops.maintenance.controller;


import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceGateService;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceRunnerService;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceSchedulerService;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceSessionService;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateCloseRequest;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateCreateRequest;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSchedulerStatusResponse;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSessionInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.maintenance.MaintenanceGateCheckKind;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateDecision;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MaintenanceControllerTest {

    private final MaintenanceSessionService maintenanceSessionService = org.mockito.Mockito.mock(MaintenanceSessionService.class);
    private final MaintenanceRunnerService maintenanceRunnerService = org.mockito.Mockito.mock(MaintenanceRunnerService.class);
    private final MaintenanceSchedulerService maintenanceSchedulerService =
            org.mockito.Mockito.mock(MaintenanceSchedulerService.class);
    private final MaintenanceGateService maintenanceGateService = org.mockito.Mockito.mock(MaintenanceGateService.class);
    private final MaintenanceController controller = new MaintenanceController(
            maintenanceSessionService,
            maintenanceRunnerService,
            maintenanceSchedulerService,
            maintenanceGateService);

    @Test
    void activeReturnsNoContentWhenNoSessionExists() {
        when(maintenanceSessionService.activeSession()).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NO_CONTENT, controller.active().getStatusCode());
    }

    @Test
    void activeReturnsSessionWhenPresent() {
        MaintenanceSessionInfo session = new MaintenanceSessionInfo(UUID.randomUUID(), "任务", 100L, false);
        when(maintenanceSessionService.activeSession()).thenReturn(Optional.of(session));

        assertEquals(session, controller.active().getBody());
    }

    @Test
    void initRoutesReturnSessionId() {
        UUID id = UUID.randomUUID();
        when(maintenanceSessionService.initSession("sanitize"))
                .thenReturn(new MaintenanceSessionInfo(id, "sanitize", 100L, false));

        assertEquals(id.toString(), controller.initSanitize().getBody().get("sessionId"));
    }

    @Test
    void fullSearchReindexInitRoutesReturnSessionIds() {
        UUID rawId = UUID.randomUUID();
        UUID unifiedId = UUID.randomUUID();
        when(maintenanceSessionService.initSession("search-reindex-raw-all"))
                .thenReturn(new MaintenanceSessionInfo(rawId, "search-reindex-raw-all", 100L, false));
        when(maintenanceSessionService.initSession("search-reindex-unified-all"))
                .thenReturn(new MaintenanceSessionInfo(unifiedId, "search-reindex-unified-all", 100L, false));

        assertEquals(rawId.toString(), controller.initRawReindexAll().getBody().get("sessionId"));
        assertEquals(unifiedId.toString(), controller.initUnifiedReindexAll().getBody().get("sessionId"));
    }

    @Test
    void runnersExposeRegistryMetadata() {
        MaintenanceRunnerMetadata metadata = new MaintenanceRunnerMetadata(
                "search-reindex-unified",
                MaintenanceRiskLevel.LOW,
                true,
                true,
                5,
                100,
                "");
        when(maintenanceRunnerService.registry()).thenReturn(List.of(metadata));

        Iterable<MaintenanceRunnerMetadata> body = controller.runners().getBody();

        assertEquals(List.of(metadata), body);
    }

    @Test
    void schedulerStatusAndRunOnceAreExposed() {
        MaintenanceSchedulerStatusResponse status = new MaintenanceSchedulerStatusResponse(
                false,
                true,
                true,
                false,
                true,
                "ircs-ops-service@pod#1",
                List.of("search-reindex-unified"),
                null,
                null,
                null,
                List.of());
        when(maintenanceSchedulerService.status()).thenReturn(status);
        when(maintenanceSchedulerService.runOnce()).thenReturn(status);

        assertEquals(status, controller.scheduler().getBody());
        assertEquals(status, controller.runSchedulerOnce().getBody());
    }

    @Test
    void maintenanceGateEndpointsDelegateToGateService() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-11T10:15:30Z");
        MaintenanceGateResponse response = new MaintenanceGateResponse(
                id,
                now,
                now,
                0,
                "media-path-migration",
                "storage-service",
                "media",
                "*",
                MaintenanceGateMode.QUIESCE_WRITES,
                MaintenanceGateStatus.ACTIVE,
                "moving paths",
                "admin",
                "corr",
                now.plusSeconds(600),
                null,
                "");
        MaintenanceGateCreateRequest createRequest = new MaintenanceGateCreateRequest(
                "media-path-migration",
                "storage-service",
                "media",
                "*",
                MaintenanceGateMode.QUIESCE_WRITES,
                "moving paths",
                "admin",
                "corr",
                null,
                600L);
        MaintenanceGateCloseRequest closeRequest = new MaintenanceGateCloseRequest("done");
        MaintenanceGateDecision decision = MaintenanceGateDecision.blocked(
                MaintenanceGateCheckKind.WRITE,
                id,
                "media-path-migration",
                "storage-service",
                "media",
                "*",
                MaintenanceGateMode.QUIESCE_WRITES,
                "moving paths",
                now.plusSeconds(600));
        when(maintenanceGateService.active()).thenReturn(List.of(response));
        when(maintenanceGateService.create(createRequest)).thenReturn(response);
        when(maintenanceGateService.close(id, closeRequest)).thenReturn(response);
        when(maintenanceGateService.check("storage-service", "media", "*", MaintenanceGateCheckKind.WRITE))
                .thenReturn(decision);

        assertEquals(List.of(response), controller.gates().getBody());
        assertEquals(201, controller.createGate(createRequest).getStatusCode().value());
        assertEquals(response, controller.closeGate(id, closeRequest).getBody());
        assertEquals(decision, controller.checkGate("storage-service", "media", "*", MaintenanceGateCheckKind.WRITE).getBody());
    }

    @Test
    void trendSyncReturnsAccepted() {
        when(maintenanceSessionService.initSession("trend-sync"))
                .thenReturn(new MaintenanceSessionInfo(UUID.randomUUID(), "trend-sync", 100L, false));

        assertTrue(controller.triggerTrendSync().getStatusCode().is2xxSuccessful());
        assertEquals(202, controller.triggerTrendSync().getStatusCode().value());
    }
}
