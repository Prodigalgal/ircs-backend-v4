package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class AggregationInternalAccessPolicy {

    @Value("${app.aggregation.internal-access.require-token:false}")
    private boolean requireToken;

    @Value("${app.aggregation.internal-access.token:${APP_AGGREGATION_SERVICE_TOKEN:}}")
    private String configuredToken;

    @Value("${app.aggregation.internal-access.required-scope:aggregation:maintenance}")
    private String requiredScope;

    void assertAccess(String serviceId, String serviceToken, String serviceScopes) {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            return;
        }
        InternalServiceAccessPolicy.require(
                "Aggregation",
                configuredToken,
                requiredScope,
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
