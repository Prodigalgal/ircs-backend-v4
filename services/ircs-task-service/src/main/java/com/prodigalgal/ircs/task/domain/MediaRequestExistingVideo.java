package com.prodigalgal.ircs.task.domain;

import java.util.UUID;

public record MediaRequestExistingVideo(
        UUID id,
        String source) {
}
