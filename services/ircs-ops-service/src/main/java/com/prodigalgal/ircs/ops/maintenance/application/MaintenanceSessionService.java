package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.common.concurrent.VirtualThreadExecutors;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunnerExecution;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceEvent;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceSessionInfo;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class MaintenanceSessionService {

    private final MaintenanceRunnerService maintenanceRunnerService;
    private final MaintenanceNotificationPublisher notificationPublisher;
    private final ExecutorService streamExecutor = VirtualThreadExecutors.newPerTaskExecutor("maintenance-session-");
    private final ConcurrentMap<UUID, MaintenanceSessionInfo> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> startedSessions = ConcurrentHashMap.newKeySet();

    public Optional<MaintenanceSessionInfo> activeSession() {
        return sessions.values().stream()
                .filter(session -> !session.finished())
                .findFirst();
    }

    public MaintenanceSessionInfo initSession(String taskName) {
        MaintenanceSessionInfo session = new MaintenanceSessionInfo(
                IrcsUuidGenerators.nextId(),
                taskName,
                System.currentTimeMillis(),
                false);
        sessions.put(session.sessionId(), session);
        return session;
    }

    public SseEmitter stream(UUID sessionId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        MaintenanceSessionInfo session = sessions.get(sessionId);
        if (session == null) {
            streamExecutor.execute(() -> sendExpiredSession(emitter));
        } else if (session.finished()) {
            streamExecutor.execute(() -> sendCompletedSession(emitter, session));
        } else if (!startedSessions.add(sessionId)) {
            streamExecutor.execute(() -> sendAlreadyRunningSession(emitter, session));
        } else {
            streamExecutor.execute(() -> sendSession(emitter, session));
        }
        return emitter;
    }

    private void sendSession(SseEmitter emitter, MaintenanceSessionInfo session) {
        try {
            emitter.send(MaintenanceEvent.log("已创建 dev guarded maintenance session: " + session.taskName()));
            MaintenanceRunnerExecution execution = maintenanceRunnerService.run(
                    session.taskName(),
                    session.sessionId().toString());
            if (execution.refused()) {
                emitter.send(MaintenanceEvent.warn("maintenance runner refused: "
                        + session.taskName()
                        + " reason="
                        + execution.reason()));
                notificationPublisher.publishManualRun(session.sessionId(), session.taskName(), execution);
            } else {
                emitter.send(MaintenanceEvent.log("执行 dev-safe maintenance runner: " + session.taskName()));
                MaintenanceRunResult result = execution.result();
                if (result.selectedCount() == 0) {
                    emitter.send(MaintenanceEvent.warn("maintenance runner 未选择可处理记录: " + result.taskName()));
                } else {
                    emitter.send(MaintenanceEvent.progress(result.publishedCount(), result.selectedCount()));
                    emitter.send(MaintenanceEvent.log("maintenance runner 已完成: "
                            + result.taskName()
                            + " selected="
                            + result.selectedCount()
                            + " changed="
                            + result.publishedCount()));
                }
                notificationPublisher.publishManualRun(session.sessionId(), session.taskName(), execution);
            }
            markFinished(session);
            emitter.send(MaintenanceEvent.done());
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (Exception e) {
            sendFailedSession(emitter, session, e);
        } finally {
            startedSessions.remove(session.sessionId());
        }
    }

    private void sendExpiredSession(SseEmitter emitter) {
        try {
            emitter.send(MaintenanceEvent.error("任务会话已过期或不存在"));
            emitter.send(MaintenanceEvent.done());
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendCompletedSession(SseEmitter emitter, MaintenanceSessionInfo session) {
        try {
            emitter.send(MaintenanceEvent.log("任务已完成: " + session.taskName()));
            emitter.send(MaintenanceEvent.done());
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendAlreadyRunningSession(SseEmitter emitter, MaintenanceSessionInfo session) {
        try {
            emitter.send(MaintenanceEvent.warn("任务已在执行中，请保持已有 SSE 连接: " + session.taskName()));
            emitter.send(MaintenanceEvent.done());
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendFailedSession(SseEmitter emitter, MaintenanceSessionInfo session, Exception exception) {
        try {
            markFinished(session);
            notificationPublisher.publishManualFailure(session.sessionId(), session.taskName(), exception);
            emitter.send(MaintenanceEvent.error("维护任务执行失败: " + exception.getMessage()));
            emitter.send(MaintenanceEvent.done());
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void markFinished(MaintenanceSessionInfo session) {
        sessions.put(session.sessionId(), new MaintenanceSessionInfo(
                session.sessionId(),
                session.taskName(),
                session.startTime(),
                true));
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
    }
}
