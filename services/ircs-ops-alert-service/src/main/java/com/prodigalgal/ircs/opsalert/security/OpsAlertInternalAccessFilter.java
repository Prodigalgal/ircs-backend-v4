package com.prodigalgal.ircs.opsalert.security;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
class OpsAlertInternalAccessFilter extends OncePerRequestFilter {

    static final String REQUIRE_TOKEN_KEY = "app.ops-alert.internal-access.require-token";
    static final String TOKEN_KEY = "app.ops-alert.internal-access.token";
    static final String READ_SCOPE_KEY = "app.ops-alert.internal-access.read-scope";
    static final String RUN_SCOPE_KEY = "app.ops-alert.internal-access.run-scope";

    private final RuntimeConfigService runtimeConfig;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/ops-alert");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String configuredToken = runtimeConfig.stringValue(TOKEN_KEY, "");
        boolean requireToken = runtimeConfig.booleanValue(REQUIRE_TOKEN_KEY, false);
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            InternalServiceAccessPolicy.require(
                    "Ops alert",
                    configuredToken,
                    requiredScope(request.getMethod()),
                    request.getHeader(InternalServiceAuthHeaders.SERVICE_ID),
                    request.getHeader(InternalServiceAuthHeaders.SERVICE_TOKEN),
                    request.getHeader(InternalServiceAuthHeaders.SERVICE_SCOPES));
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException ex) {
            ApiErrorResponses.write(
                    request,
                    response,
                    ex.getStatusCode(),
                    "ops-alert.internal.access.denied",
                    "Ops alert internal access denied",
                    "ops-alert");
        }
    }

    private String requiredScope(String method) {
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return runtimeConfig.stringValue(READ_SCOPE_KEY, "ops-alert:read");
        }
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return null;
        }
        return runtimeConfig.stringValue(RUN_SCOPE_KEY, "ops-alert:run");
    }
}
