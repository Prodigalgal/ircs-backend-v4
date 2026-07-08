package com.prodigalgal.ircs.identity.security;



import com.prodigalgal.ircs.identity.application.PoWService;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class PoWEnforcementFilter extends OncePerRequestFilter {

    private static final Map<String, Requirement> REQUIREMENTS = Map.of(
            "/api/v1/auth/login", new Requirement("admin.login", true),
            "/api/portal/auth/login", new Requirement("portal.login", true),
            "/api/portal/auth/register", new Requirement("portal.register", false),
            "/api/portal/auth/activate", new Requirement("portal.activate", false),
            "/api/portal/auth/resend-code", new Requirement("portal.resend-code", false),
            "/api/portal/auth/forgot-password", new Requirement("portal.forgot-password", false),
            "/api/portal/auth/reset-password", new Requirement("portal.reset-password", false));

    private final PoWService poWService;
    private final RuntimeConfigService runtimeConfig;
    private final boolean enabledByDeployment;

    PoWEnforcementFilter(
            PoWService poWService,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.identity.pow.enabled:true}") boolean enabled) {
        this.poWService = poWService;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.enabledByDeployment = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !enabled()) {
            return true;
        }
        return !REQUIREMENTS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Requirement requirement = REQUIREMENTS.get(request.getRequestURI());
        try {
            poWService.verifyHeaders(request, requirement.scope(), requirement.captchaRequired());
        } catch (ApiException ex) {
            ApiErrorResponses.write(
                    request,
                    response,
                    ex.status(),
                    ex.errorKey(),
                    ex.getMessage(),
                    ex.entity());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Requirement(String scope, boolean captchaRequired) {
    }

    private boolean enabled() {
        return runtimeConfig == null
                ? enabledByDeployment
                : runtimeConfig.booleanValue("app.identity.pow.enabled", enabledByDeployment);
    }
}
