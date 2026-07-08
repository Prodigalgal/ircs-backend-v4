package com.prodigalgal.ircs.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

public final class InternalServiceAccessPolicy {

    private InternalServiceAccessPolicy() {
    }

    public static void require(
            String targetName,
            String configuredToken,
            String requiredScope,
            String serviceId,
            String serviceToken,
            String serviceScopes) {
        if (!StringUtils.hasText(configuredToken)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    targetName + " internal service token is not configured");
        }
        if (!StringUtils.hasText(serviceId)
                || !StringUtils.hasText(serviceToken)
                || !constantTimeEquals(configuredToken, serviceToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service identity");
        }
        if (StringUtils.hasText(requiredScope) && !hasScope(serviceScopes, requiredScope)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal service scope is missing");
        }
    }

    public static boolean hasScope(String serviceScopes, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            return true;
        }
        if (!StringUtils.hasText(serviceScopes)) {
            return false;
        }
        String normalizedRequired = requiredScope.trim();
        return Arrays.stream(serviceScopes.split("[,\\s]+"))
                .map(String::trim)
                .anyMatch(scope -> scope.equals(normalizedRequired) || scope.equals("*"));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8));
    }
}
