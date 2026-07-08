package com.prodigalgal.ircs.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

public class IrcsJwtRequestAuthenticator {

    private final IrcsJwtRuntimeConfigResolver configResolver;
    private final IrcsJwtAuthDecoder decoder;

    public IrcsJwtRequestAuthenticator(IrcsJwtRuntimeConfigResolver configResolver) {
        this(configResolver, new IrcsJwtAuthDecoder());
    }

    IrcsJwtRequestAuthenticator(IrcsJwtRuntimeConfigResolver configResolver, IrcsJwtAuthDecoder decoder) {
        this.configResolver = configResolver;
        this.decoder = decoder;
    }

    public Optional<IrcsRequestPrincipal> authenticateIfPresent(HttpServletRequest request) {
        return resolveToken(request).map(token -> decoder.decode(
                token,
                configResolver.jwtSecret(),
                configResolver.iatFloorSeconds()));
    }

    public IrcsRequestPrincipal require(HttpServletRequest request) {
        String token = resolveToken(request)
                .orElseThrow(() -> new IrcsAuthException(IrcsAuthException.Reason.MISSING, "缺少登录凭证"));
        return decoder.decode(token, configResolver.jwtSecret(), configResolver.iatFloorSeconds());
    }

    public IrcsRequestPrincipal requireRole(HttpServletRequest request, String role) {
        IrcsRequestPrincipal principal = require(request);
        String required = IrcsPermissions.normalizeRole(role);
        if (!required.equals(principal.role())) {
            throw new IrcsAuthException(IrcsAuthException.Reason.FORBIDDEN, "权限不足");
        }
        return principal;
    }

    public IrcsRequestPrincipal requireRoleAndPermission(HttpServletRequest request, String role, String permission) {
        IrcsRequestPrincipal principal = requireRole(request, role);
        if (!principal.hasPermission(permission)) {
            throw new IrcsAuthException(IrcsAuthException.Reason.FORBIDDEN, "权限不足");
        }
        return principal;
    }

    public IrcsRequestPrincipal requirePermission(HttpServletRequest request, String permission) {
        IrcsRequestPrincipal principal = require(request);
        if (!principal.hasPermission(permission)) {
            throw new IrcsAuthException(IrcsAuthException.Reason.FORBIDDEN, "权限不足");
        }
        return principal;
    }

    private static Optional<String> resolveToken(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            if (StringUtils.hasText(token)) {
                return Optional.of(token);
            }
        }
        if (shouldResolveQueryToken(request)) {
            String queryToken = request.getParameter("token");
            if (StringUtils.hasText(queryToken)) {
                return Optional.of(queryToken.trim());
            }
        }
        return Optional.empty();
    }

    private static boolean shouldResolveQueryToken(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
            return true;
        }
        String path = request.getRequestURI();
        return path != null && (path.endsWith("/stream") || path.contains("/stream/"));
    }
}
