package com.prodigalgal.ircs.common.audit;

import java.time.Duration;

public record WorkerJobAuditEvent(
        String jobType,
        String jobName,
        String correlationId,
        String status,
        Duration duration,
        Throwable error) {

    public static WorkerJobAuditEvent succeeded(
            String jobType,
            String jobName,
            String correlationId,
            Duration duration) {
        return new WorkerJobAuditEvent(jobType, jobName, correlationId, "succeeded", duration, null);
    }

    public static WorkerJobAuditEvent failed(
            String jobType,
            String jobName,
            String correlationId,
            Duration duration,
            Throwable error) {
        return new WorkerJobAuditEvent(jobType, jobName, correlationId, "failed", duration, error);
    }
}
