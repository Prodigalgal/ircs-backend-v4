package com.prodigalgal.ircs.ops.selfhealing;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.restart.application.KubernetesDeploymentRestartService;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ServiceSelfHealingService {

    static final String ENABLED_KEY = "app.ops.self-healing.service-restart.enabled";
    static final String DEFAULT_DRY_RUN_KEY = "app.ops.self-healing.service-restart.default-dry-run";
    static final String COOLDOWN_KEY = "app.ops.self-healing.service-restart.cooldown";
    static final String MAX_ATTEMPTS_KEY = "app.ops.self-healing.service-restart.max-attempts-per-window";
    static final String ATTEMPT_WINDOW_KEY = "app.ops.self-healing.service-restart.attempt-window";

    private final KubernetesDeploymentRestartService restartService;
    private final RuntimeConfigService runtimeConfig;
    private final Clock clock;
    private final Map<String, RestartAttemptState> attempts = new ConcurrentHashMap<>();

    public ServiceRestartHealingResponse restart(ServiceRestartHealingRequest request) {
        String service = requireText(request == null ? null : request.service(), "service");
        String reason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "IRCS self-healing";
        Instant now = clock.instant();
        boolean dryRun = effectiveDryRun(request);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("service", service);
        evidence.put("reason", reason);

        if (dryRun) {
            return response(service, true, false, false, "DRY_RUN",
                    "Dry-run only; no service restart requested.", evidence, now);
        }
        if (!runtimeConfig.booleanValue(ENABLED_KEY, false)) {
            return response(service, false, false, false, "SKIPPED",
                    "Service self-healing restart is disabled.", evidence, now);
        }
        String guardFailure = guard(service, now);
        if (guardFailure != null) {
            return response(service, false, false, false, "RATE_LIMITED", guardFailure, evidence, now);
        }

        ServiceRestartResponse restart = restartService.restart(List.of(service), reason);
        ServiceRestartResult result = restart.results().stream()
                .filter(item -> service.equals(item.service()))
                .findFirst()
                .orElseGet(() -> ServiceRestartResult.rejected(service, "Restart service returned no result"));
        evidence.put("namespace", restart.namespace());
        evidence.put("restartMessage", result.message());
        if (!result.accepted()) {
            return response(service, false, false, false, "REJECTED", result.message(), evidence, now);
        }
        recordAcceptedAttempt(service, now);
        return response(service, false, true, true, "ACCEPTED",
                "Service restart accepted by restart service.", evidence, now);
    }

    private boolean effectiveDryRun(ServiceRestartHealingRequest request) {
        if (request == null || request.dryRun() == null || request.dryRun()) {
            return true;
        }
        return runtimeConfig.booleanValue(DEFAULT_DRY_RUN_KEY, true);
    }

    private String guard(String service, Instant now) {
        RestartAttemptState state = attempts.computeIfAbsent(service, ignored -> new RestartAttemptState());
        synchronized (state) {
            Duration cooldown = positiveDuration(COOLDOWN_KEY, Duration.ofMinutes(10));
            if (state.lastAcceptedAt != null && now.isBefore(state.lastAcceptedAt.plus(cooldown))) {
                return "Service restart is in cooldown.";
            }
            Duration attemptWindow = positiveDuration(ATTEMPT_WINDOW_KEY, Duration.ofHours(1));
            Instant threshold = now.minus(attemptWindow);
            while (!state.acceptedAttempts.isEmpty() && state.acceptedAttempts.peekFirst().isBefore(threshold)) {
                state.acceptedAttempts.removeFirst();
            }
            int maxAttempts = Math.max(1, runtimeConfig.intValue(MAX_ATTEMPTS_KEY, 1));
            if (state.acceptedAttempts.size() >= maxAttempts) {
                return "Service restart max attempts per window reached.";
            }
            return null;
        }
    }

    private void recordAcceptedAttempt(String service, Instant now) {
        RestartAttemptState state = attempts.computeIfAbsent(service, ignored -> new RestartAttemptState());
        synchronized (state) {
            state.lastAcceptedAt = now;
            state.acceptedAttempts.addLast(now);
        }
    }

    private Duration positiveDuration(String key, Duration fallback) {
        Duration value = runtimeConfig.durationValue(key, fallback);
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static ServiceRestartHealingResponse response(
            String service,
            boolean dryRun,
            boolean accepted,
            boolean recoveryVerified,
            String status,
            String reason,
            Map<String, Object> evidence,
            Instant requestedAt) {
        return new ServiceRestartHealingResponse(
                service,
                dryRun,
                accepted,
                recoveryVerified,
                status,
                reason,
                evidence,
                requestedAt);
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static final class RestartAttemptState {
        private Instant lastAcceptedAt;
        private final Deque<Instant> acceptedAttempts = new ArrayDeque<>();
    }
}
