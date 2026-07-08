package com.prodigalgal.ircs.ops.security;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
class OpsInternalAccessFilter extends OncePerRequestFilter {

    private final boolean requireToken;
    private final String configuredToken;
    private final String readScope;
    private final String runScope;

    OpsInternalAccessFilter(
            @Value("${app.ops.internal-access.require-token:false}") boolean requireToken,
            @Value("${app.ops.internal-access.token:${APP_OPS_SERVICE_TOKEN:}}") String configuredToken,
            @Value("${app.ops.internal-access.read-scope:ops:read}") String readScope,
            @Value("${app.ops.internal-access.run-scope:ops:run}") String runScope) {
        this.requireToken = requireToken;
        this.configuredToken = configuredToken;
        this.readScope = readScope;
        this.runScope = runScope;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/ops")
                || path.startsWith("/api/v1/dashboard")
                || path.startsWith("/api/v1/debug"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!requireToken && !StringUtils.hasText(configuredToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            InternalServiceAccessPolicy.require(
                    "Ops",
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
                    "ops.internal.access.denied",
                    "Ops internal access denied",
                    "ops");
        }
    }

    private String requiredScope(String method) {
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return readScope;
        }
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return null;
        }
        return runScope;
    }
}
