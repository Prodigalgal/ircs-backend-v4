package com.prodigalgal.ircs.task.domain;

import java.util.UUID;

public record TaskConfigurationChangedEvent(UUID taskId, boolean deleted) {
}
