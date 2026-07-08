package com.prodigalgal.ircs.common.readiness;

import org.springframework.util.StringUtils;

public record AutoStartGate(String name, String propertyKey, String environmentKey, boolean defaultEnabled) {

    public AutoStartGate {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!StringUtils.hasText(propertyKey)) {
            throw new IllegalArgumentException("propertyKey must not be blank");
        }
    }
}
