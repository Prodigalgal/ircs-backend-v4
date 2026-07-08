package com.prodigalgal.ircs.search.sync;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class SearchInternalAccessPolicy {

    @Value("${app.search.internal-access.require-token:false}")
    private boolean requireToken;

    @Value("${app.search.internal-access.token:${APP_SEARCH_SERVICE_TOKEN:}}")
    private String configuredToken;

    @Value("${app.search.internal-access.required-scope:search:sync}")
    private String requiredScope;

    public void assertAccess(String serviceId, String serviceToken, String serviceScopes) {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            return;
        }
        InternalServiceAccessPolicy.require(
                "Search",
                configuredToken,
                requiredScope,
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
