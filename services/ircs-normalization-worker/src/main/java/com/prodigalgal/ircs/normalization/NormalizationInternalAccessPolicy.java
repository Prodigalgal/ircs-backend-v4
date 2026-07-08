package com.prodigalgal.ircs.normalization;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class NormalizationInternalAccessPolicy {

    @Value("${app.normalization.internal-access.require-token:false}")
    private boolean requireToken;

    @Value("${app.normalization.internal-access.token:${APP_NORMALIZATION_SERVICE_TOKEN:}}")
    private String configuredToken;

    @Value("${app.normalization.internal-access.required-scope:normalization:maintenance}")
    private String requiredScope;

    void assertAccess(String serviceId, String serviceToken, String serviceScopes) {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            return;
        }
        InternalServiceAccessPolicy.require(
                "Normalization",
                configuredToken,
                requiredScope,
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
