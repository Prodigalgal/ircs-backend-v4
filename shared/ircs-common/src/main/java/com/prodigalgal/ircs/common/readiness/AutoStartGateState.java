package com.prodigalgal.ircs.common.readiness;

public record AutoStartGateState(
        String name,
        String propertyKey,
        String environmentKey,
        boolean enabled,
        String source,
        String sourceKey,
        boolean blocked) {
}
