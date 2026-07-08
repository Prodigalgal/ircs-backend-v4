package com.prodigalgal.ircs.opsalert.domain;

public record HealingPlan(
        String policyKey,
        String playbookKey,
        boolean dryRun,
        HealingActionStatus status,
        String requestPayload,
        String resultPayload) {
}
