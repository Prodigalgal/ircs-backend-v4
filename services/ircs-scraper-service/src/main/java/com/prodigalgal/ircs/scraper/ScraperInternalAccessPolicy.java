package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class ScraperInternalAccessPolicy {

    @Value("${app.scraper.internal-access.require-token:false}")
    private boolean requireToken;

    @Value("${app.scraper.internal-access.token:${APP_SCRAPER_SERVICE_TOKEN:}}")
    private String configuredToken;

    @Value("${app.scraper.internal-access.required-scope:scraper:maintenance}")
    private String requiredScope;

    void assertAccess(String serviceId, String serviceToken, String serviceScopes) {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            return;
        }
        InternalServiceAccessPolicy.require(
                "Scraper",
                configuredToken,
                requiredScope,
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
