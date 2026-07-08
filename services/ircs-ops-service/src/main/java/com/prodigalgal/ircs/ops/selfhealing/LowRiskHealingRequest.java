package com.prodigalgal.ircs.ops.selfhealing;

public record LowRiskHealingRequest(
        LowRiskHealingPlaybook playbook,
        Boolean dryRun,
        String taskType,
        String queueName,
        Integer limit,
        Integer maxReplayAttempts,
        Integer days) {
}
