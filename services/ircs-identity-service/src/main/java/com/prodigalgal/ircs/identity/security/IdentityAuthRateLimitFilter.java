package com.prodigalgal.ircs.identity.security;


import com.prodigalgal.ircs.identity.IdentityRedisKeys;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IdentityAuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> RATE_LIMITED_POST_PATHS = Set.of(
            "/api/portal/auth/register",
            "/api/portal/auth/login",
            "/api/portal/auth/forgot-password",
            "/api/portal/auth/reset-password",
            "/api/portal/auth/activate",
            "/api/portal/auth/resend-code",
            "/api/v1/auth/login");

    private final StringRedisTemplate redisTemplate;
    private final RuntimeConfigService runtimeConfig;
    private final boolean enabledByDeployment;
    private final int maxAttemptsByDeployment;
    private final Duration windowByDeployment;

    public IdentityAuthRateLimitFilter(
            StringRedisTemplate redisTemplate,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.identity.auth-rate-limit.enabled:true}") boolean enabled,
            @Value("${app.identity.auth-rate-limit.max-attempts:30}") int maxAttempts,
            @Value("${app.identity.auth-rate-limit.window:PT5M}") Duration window) {
        this.redisTemplate = redisTemplate;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.enabledByDeployment = enabled;
        this.maxAttemptsByDeployment = Math.max(1, maxAttempts);
        this.windowByDeployment = window == null || window.isZero() || window.isNegative()
                ? Duration.ofMinutes(5)
                : window;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled() || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !RATE_LIMITED_POST_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String bucket = request.getRequestURI() + ":" + clientIp(request);
        String key = IdentityRedisKeys.authRateLimit(bucket);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window());
        }
        if (count != null && count > maxAttempts()) {
            ApiErrorResponses.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "auth.rate.limit",
                    "Too many authentication attempts",
                    "auth");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp.trim() : request.getRemoteAddr();
    }

    private boolean enabled() {
        return runtimeConfig == null
                ? enabledByDeployment
                : runtimeConfig.booleanValue("app.identity.auth-rate-limit.enabled", enabledByDeployment);
    }

    private int maxAttempts() {
        int value = runtimeConfig == null
                ? maxAttemptsByDeployment
                : runtimeConfig.intValue("app.identity.auth-rate-limit.max-attempts", maxAttemptsByDeployment);
        return Math.max(1, value);
    }

    private Duration window() {
        Duration value = runtimeConfig == null
                ? windowByDeployment
                : runtimeConfig.durationValue("app.identity.auth-rate-limit.window", windowByDeployment);
        return value == null || value.isZero() || value.isNegative() ? windowByDeployment : value;
    }
}
