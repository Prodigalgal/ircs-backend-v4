package com.prodigalgal.ircs.common.audit;

import java.util.Locale;
import org.springframework.util.StringUtils;

public final class AuditClassifiers {

    private AuditClassifiers() {
    }

    public static AuditClass request(String path) {
        if (!StringUtils.hasText(path)) {
            return AuditClass.SYSTEM;
        }
        String normalized = path.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/internal/")
                || normalized.equals("/internal")
                || normalized.startsWith("/api/v1/ops/")
                || normalized.equals("/api/v1/ops")) {
            return AuditClass.SYSTEM;
        }
        if (containsAny(
                normalized,
                "/auth",
                "/login",
                "/logout",
                "/register",
                "/session",
                "/sessions",
                "/token",
                "/password",
                "/credentials",
                "/permission",
                "/permissions",
                "/rbac",
                "/members",
                "/profile",
                "/avatar")) {
            return AuditClass.SECURITY;
        }
        return AuditClass.BEHAVIOR;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
