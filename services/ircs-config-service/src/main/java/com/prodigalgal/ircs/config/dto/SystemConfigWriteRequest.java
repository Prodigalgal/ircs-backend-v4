package com.prodigalgal.ircs.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemConfigWriteRequest(
        @NotBlank @Size(max = 100) String key,
        @Size(max = 20000) String value,
        @Size(max = 255) String description) {
}
