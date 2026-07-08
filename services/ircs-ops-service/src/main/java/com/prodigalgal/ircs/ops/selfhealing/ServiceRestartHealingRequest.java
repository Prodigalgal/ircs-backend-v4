package com.prodigalgal.ircs.ops.selfhealing;

public record ServiceRestartHealingRequest(
        String service,
        String reason,
        Boolean dryRun) {
}
