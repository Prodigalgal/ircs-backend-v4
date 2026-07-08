package com.prodigalgal.ircs.ops.maintenance.application;


import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRiskLevel;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceRunnerMetadata;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSessionInfo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceSessionServiceTest {

    private final MaintenanceRunnerService runnerService =
            org.mockito.Mockito.mock(MaintenanceRunnerService.class);
    private final MaintenanceNotificationPublisher notificationPublisher =
            org.mockito.Mockito.mock(MaintenanceNotificationPublisher.class);
    private final MaintenanceSessionService sessionService =
            new MaintenanceSessionService(runnerService, notificationPublisher);

    @Test
    void streamRunsSupportedMaintenanceRunner() {
        UUID indexedId = UUID.randomUUID();
        MaintenanceSessionInfo session = sessionService.initSession("search-reindex-unified");
        when(runnerService.run("search-reindex-unified", session.sessionId().toString()))
                .thenReturn(MaintenanceRunnerExecution.executed(
                        metadata("search-reindex-unified", MaintenanceRiskLevel.LOW, true),
                        new MaintenanceRunResult(
                                "search-reindex-unified",
                                1,
                                1,
                                List.of(indexedId))));

        sessionService.stream(session.sessionId());

        verify(runnerService, timeout(1000)).run("search-reindex-unified", session.sessionId().toString());
        verify(notificationPublisher, timeout(1000)).publishManualRun(
                org.mockito.Mockito.eq(session.sessionId()),
                org.mockito.Mockito.eq("search-reindex-unified"),
                org.mockito.Mockito.any());
        assertTrue(awaitNoActiveSession());
    }

    @Test
    void streamKeepsUnsupportedMaintenanceTaskGuarded() {
        MaintenanceSessionInfo session = sessionService.initSession("area-clean");
        when(runnerService.run("area-clean", session.sessionId().toString()))
                .thenReturn(MaintenanceRunnerExecution.refused(
                        metadata("area-clean", MaintenanceRiskLevel.HIGH, false),
                        MaintenanceRunnerService.DEV_REFUSAL_REASON));

        sessionService.stream(session.sessionId());

        verify(runnerService, timeout(1000)).run("area-clean", session.sessionId().toString());
        verify(notificationPublisher, timeout(1000)).publishManualRun(
                org.mockito.Mockito.eq(session.sessionId()),
                org.mockito.Mockito.eq("area-clean"),
                org.mockito.Mockito.any());
        assertTrue(awaitNoActiveSession());
    }

    private static MaintenanceRunnerMetadata metadata(
            String taskName,
            MaintenanceRiskLevel riskLevel,
            boolean devAllowed) {
        return new MaintenanceRunnerMetadata(
                taskName,
                riskLevel,
                devAllowed,
                devAllowed,
                devAllowed ? MaintenanceReindexCommand.DEFAULT_DEV_LIMIT : 0,
                devAllowed ? MaintenanceReindexCommand.MAX_DEV_LIMIT : 0,
                devAllowed ? "" : MaintenanceRunnerService.DEV_REFUSAL_REASON);
    }

    private boolean awaitNoActiveSession() {
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            if (sessionService.activeSession().isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return sessionService.activeSession().isEmpty();
    }
}
