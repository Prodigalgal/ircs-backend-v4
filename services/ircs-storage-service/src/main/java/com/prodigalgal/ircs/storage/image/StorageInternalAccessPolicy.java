package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
class StorageInternalAccessPolicy {

    private final boolean requireToken;
    private final String configuredToken;
    private final String avatarScope;

    StorageInternalAccessPolicy(
            @Value("${app.storage.internal-access.require-token:false}") boolean requireToken,
            @Value("${app.storage.internal-access.token:${APP_STORAGE_SERVICE_TOKEN:}}") String configuredToken,
            @Value("${app.storage.internal-access.avatar-scope:storage:avatar}") String avatarScope) {
        this.requireToken = requireToken;
        this.configuredToken = configuredToken;
        this.avatarScope = avatarScope;
    }

    void assertAvatarAccess(String serviceId, String serviceToken, String serviceScopes) {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            return;
        }
        try {
            InternalServiceAccessPolicy.require(
                    "Storage",
                    configuredToken,
                    avatarScope,
                    serviceId,
                    serviceToken,
                    serviceScopes);
        } catch (ResponseStatusException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            throw new StorageApiException(
                    status == null ? HttpStatus.UNAUTHORIZED : status,
                    "Storage internal access denied");
        }
    }
}
