package com.prodigalgal.ircs.common.security;

import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import org.springframework.util.StringUtils;

public final class InternalServiceAuthHeaders {

    public static final String SERVICE_ID = "X-IRCS-SERVICE-ID";
    public static final String SERVICE_TOKEN = "X-IRCS-SERVICE-TOKEN";
    public static final String SERVICE_SCOPES = "X-IRCS-SERVICE-SCOPES";

    private InternalServiceAuthHeaders() {
    }

    public static OutboundHttpRequest apply(
            OutboundHttpRequest request,
            String serviceId,
            String serviceToken,
            String scopes) {
        if (request == null) {
            return null;
        }
        OutboundHttpRequest result = request;
        if (StringUtils.hasText(serviceId)) {
            result = result.withHeader(SERVICE_ID, serviceId.trim());
        }
        if (StringUtils.hasText(serviceToken)) {
            result = result.withHeader(SERVICE_TOKEN, serviceToken.trim());
        }
        if (StringUtils.hasText(scopes)) {
            result = result.withHeader(SERVICE_SCOPES, scopes.trim());
        }
        return result;
    }
}
