package com.prodigalgal.ircs.ops.selfhealing;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.dashboard.application.DashboardQueryService;
import com.prodigalgal.ircs.ops.queue.dlq.rabbit.RabbitDlqActionResponse;
import com.prodigalgal.ircs.ops.queue.dlq.rabbit.RabbitDlqService;
import com.prodigalgal.ircs.ops.queue.dlq.runtime.RuntimeWorkDlqService;
import com.prodigalgal.ircs.ops.queue.dlq.runtime.RuntimeWorkExpiredInflightReaper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LowRiskSelfHealingService {

    static final String EXECUTE_ENABLED_KEY = "app.ops.self-healing.low-risk.execute-enabled";
    static final String DEFAULT_DRY_RUN_KEY = "app.ops.self-healing.low-risk.default-dry-run";

    private final RuntimeWorkExpiredInflightReaper expiredInflightReaper;
    private final RuntimeWorkDlqService runtimeWorkDlqService;
    private final RabbitDlqService rabbitDlqService;
    private final DashboardQueryService dashboardQueryService;
    private final RuntimeConfigService runtimeConfig;
    private final Clock clock;

    public LowRiskSelfHealingService(
            RuntimeWorkExpiredInflightReaper expiredInflightReaper,
            RuntimeWorkDlqService runtimeWorkDlqService,
            RabbitDlqService rabbitDlqService,
            DashboardQueryService dashboardQueryService,
            RuntimeConfigService runtimeConfig,
            @Qualifier("opsClock") Clock clock) {
        this.expiredInflightReaper = expiredInflightReaper;
        this.runtimeWorkDlqService = runtimeWorkDlqService;
        this.rabbitDlqService = rabbitDlqService;
        this.dashboardQueryService = dashboardQueryService;
        this.runtimeConfig = runtimeConfig;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public LowRiskHealingResponse run(LowRiskHealingRequest request) {
        LowRiskHealingPlaybook playbook = request == null ? null : request.playbook();
        if (playbook == null) {
            throw new IllegalArgumentException("playbook is required");
        }
        boolean dryRun = effectiveDryRun(request);
        if (dryRun) {
            return response(playbook, true, false, 0, "DRY_RUN", "Dry-run only; no runtime mutation performed.",
                    evidence(request));
        }
        if (!runtimeConfig.booleanValue(EXECUTE_ENABLED_KEY, false)) {
            return response(playbook, false, false, 0, "SKIPPED", "Low-risk self-healing execution is disabled.",
                    evidence(request));
        }
        return switch (playbook) {
            case RUNTIME_EXPIRED_INFLIGHT_REQUEUE -> requeueExpiredInflight(request);
            case RUNTIME_DLQ_REQUEUE_ONE -> requeueRuntimeDlq(request);
            case RABBIT_DLQ_RETRY_ONE -> retryRabbitDlq(request);
            case DASHBOARD_REFRESH -> refreshDashboard(request);
        };
    }

    private LowRiskHealingResponse requeueExpiredInflight(LowRiskHealingRequest request) {
        int affected = expiredInflightReaper.runOnce();
        return response(request.playbook(), false, true, affected, "EXECUTED",
                "Expired runtime inflight reaper executed once.", evidence(request));
    }

    private LowRiskHealingResponse requeueRuntimeDlq(LowRiskHealingRequest request) {
        String taskType = requireText(request.taskType(), "taskType");
        int maxReplayAttempts = positiveOr(request.maxReplayAttempts(), 3);
        int affected = runtimeWorkDlqService.requeueOne(taskType, maxReplayAttempts);
        Map<String, Object> evidence = evidence(request);
        evidence.put("taskType", taskType);
        evidence.put("affected", affected);
        evidence.put("maxReplayAttempts", maxReplayAttempts);
        return response(request.playbook(), false, true, affected, "EXECUTED",
                "Runtime DLQ requeue executed with limit=1.", evidence);
    }

    private LowRiskHealingResponse retryRabbitDlq(LowRiskHealingRequest request) {
        String queueName = requireText(request.queueName(), "queueName");
        RabbitDlqActionResponse result = rabbitDlqService.retry(queueName, 1);
        Map<String, Object> evidence = evidence(request);
        evidence.put("queueName", result.queueName());
        evidence.put("affected", result.affected());
        evidence.put("requested", result.requested());
        return response(request.playbook(), false, true, result.affected(), "EXECUTED",
                "Rabbit DLQ retry executed with limit=1.", evidence);
    }

    private LowRiskHealingResponse refreshDashboard(LowRiskHealingRequest request) {
        int days = Math.max(1, Math.min(positiveOr(request.days(), 7), 90));
        dashboardQueryService.refresh(days);
        Map<String, Object> evidence = evidence(request);
        evidence.put("days", days);
        return response(request.playbook(), false, true, 0, "EXECUTED",
                "Dashboard metrics refresh triggered.", evidence);
    }

    private boolean effectiveDryRun(LowRiskHealingRequest request) {
        if (request == null || request.dryRun() == null) {
            return true;
        }
        if (request.dryRun()) {
            return true;
        }
        return runtimeConfig.booleanValue(DEFAULT_DRY_RUN_KEY, true);
    }

    private LowRiskHealingResponse response(
            LowRiskHealingPlaybook playbook,
            boolean dryRun,
            boolean executed,
            int affected,
            String status,
            String reason,
            Map<String, Object> evidence) {
        return new LowRiskHealingResponse(
                playbook,
                dryRun,
                executed,
                affected,
                status,
                reason,
                evidence,
                clock.instant());
    }

    private static Map<String, Object> evidence(LowRiskHealingRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (request == null) {
            return values;
        }
        putIfPresent(values, "taskType", request.taskType());
        putIfPresent(values, "queueName", request.queueName());
        putIfPresent(values, "limit", request.limit());
        putIfPresent(values, "maxReplayAttempts", request.maxReplayAttempts());
        putIfPresent(values, "days", request.days());
        return values;
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }
}
