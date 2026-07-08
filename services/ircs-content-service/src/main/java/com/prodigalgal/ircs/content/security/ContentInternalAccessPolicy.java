package com.prodigalgal.ircs.content.security;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ContentInternalAccessPolicy {

    @Value("${app.content.internal-access.require-token:false}")
    private boolean requireToken;

    @Value("${app.content.internal-access.token:${APP_CONTENT_SERVICE_TOKEN:}}")
    private String configuredToken;

    @Value("${app.content.internal-access.required-scope:content:maintenance}")
    private String requiredScope;

    public void assertAccess(String serviceId, String serviceToken, String serviceScopes) {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            return;
        }
        InternalServiceAccessPolicy.require(
                "Content",
                configuredToken,
                requiredScope,
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
