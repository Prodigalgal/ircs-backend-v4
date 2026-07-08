package com.prodigalgal.ircs.contentsafety;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class ContentSafetyInternalAccessPolicy {

    private final ContentSafetyProperties properties;

    void assertAccess(String serviceId, String serviceToken, String serviceScopes) {
        ContentSafetyProperties.InternalAccess access = properties.internalAccess();
        if (!access.requireToken() && !StringUtils.hasText(access.token())) {
            return;
        }
        InternalServiceAccessPolicy.require(
                "Content safety",
                access.token(),
                access.requiredScope(),
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
