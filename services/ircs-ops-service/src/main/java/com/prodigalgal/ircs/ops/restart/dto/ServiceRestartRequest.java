package com.prodigalgal.ircs.ops.restart.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ServiceRestartRequest(
        @NotEmpty List<String> services,
        String reason) {
}
