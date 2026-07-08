package com.prodigalgal.ircs.task.domain;

import java.util.UUID;

public record ScheduledTaskDefinition(
        UUID id,
        String name,
        Boolean enabled,
        String cronExpression,
        String timeZone) {
}
