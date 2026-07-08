package com.prodigalgal.ircs.apigateway;

import com.prodigalgal.ircs.common.security.IrcsAuthException;
import com.prodigalgal.ircs.common.security.IrcsAuthHeaders;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PlatformApiAuthorizationFilter extends OncePerRequestFilter {

    private final ApiGatewayAuthenticator authenticator;

    PlatformApiAuthorizationFilter(ApiGatewayAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null
                || !(path.startsWith("/api/v1/")
                || path.startsWith("/api/portal/")
                || path.startsWith("/media/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            authorize(request).ifPresent(principal -> bindPrincipal(request, principal));
            filterChain.doFilter(request, response);
        } catch (IrcsAuthException ex) {
            SecurityContextHolder.clearContext();
            writeAuthError(request, response, ex);
        }
    }

    private Optional<IrcsRequestPrincipal> authorize(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return Optional.empty();
        }
        if (path.startsWith("/api/portal/")) {
            return authorizePortal(request);
        }
        if (path.startsWith("/api/v1/") || path.startsWith("/media/")) {
            return authorizeAdmin(request);
        }
        return Optional.empty();
    }

    private Optional<IrcsRequestPrincipal> authorizePortal(HttpServletRequest request) {
        Optional<String> requiredPermission = portalPermissionFor(request);
        if (requiredPermission.isPresent()) {
            return Optional.of(authenticator.requirePermission(request, requiredPermission.get()));
        }
        return authenticator.authenticateIfPresent(request)
                .filter(principal -> principal.hasPermission(IrcsPermissions.PORTAL_READ));
    }

    private Optional<IrcsRequestPrincipal> authorizeAdmin(HttpServletRequest request) {
        if (requiresAdmin(request)) {
            return Optional.of(authenticator.requireRoleAndPermission(
                    request,
                    IrcsPermissions.ROLE_ADMIN,
                    adminPermissionFor(request)));
        }
        return authenticator.authenticateIfPresent(request);
    }

    private static void bindPrincipal(HttpServletRequest request, IrcsRequestPrincipal principal) {
        IrcsAuthHeaders.setRequestAttribute(request, principal);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal.subject(),
                null,
                authorities(principal)));
    }

    private static List<SimpleGrantedAuthority> authorities(IrcsRequestPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(principal.role()));
        principal.permissions().stream()
                .map(IrcsPermissions::authority)
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return List.copyOf(authorities);
    }

    private static boolean requiresAdmin(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/api/v1/auth/pow".equals(path) || "/api/v1/auth/login".equals(path));
    }

    private static Optional<String> portalPermissionFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/portal/profile")) {
            return Optional.of(isRead(method)
                    ? IrcsPermissions.PORTAL_PROFILE_READ
                    : IrcsPermissions.PORTAL_PROFILE_WRITE);
        }
        if (path.startsWith("/api/portal/interaction") || path.startsWith("/api/portal/media-requests")) {
            return Optional.of(isRead(method)
                    ? IrcsPermissions.PORTAL_INTERACTION_READ
                    : IrcsPermissions.PORTAL_INTERACTION_WRITE);
        }
        if (path.startsWith("/api/portal/feedback/wall")) {
            return Optional.empty();
        }
        if (path.startsWith("/api/portal/feedback")) {
            return Optional.of(isRead(method)
                    ? IrcsPermissions.PORTAL_INTERACTION_READ
                    : IrcsPermissions.PORTAL_INTERACTION_WRITE);
        }
        return Optional.empty();
    }

    private static String adminPermissionFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/auth/change-password")) {
            return IrcsPermissions.ADMIN_AUTH_CHANGE_PASSWORD;
        }
        if (path.startsWith("/api/v1/members")) {
            return isRead(method) ? IrcsPermissions.ADMIN_MEMBERS_READ : IrcsPermissions.ADMIN_MEMBERS_WRITE;
        }
        if (path.startsWith("/api/v1/raw-videos")
                || path.startsWith("/api/v1/unified-videos")
                || path.startsWith("/api/v1/playlists")
                || path.startsWith("/api/v1/source-domains")
                || path.startsWith("/api/v1/resolvers")
                || path.startsWith("/api/v1/aggregation")) {
            return isRead(method) ? IrcsPermissions.CONTENT_READ : IrcsPermissions.CONTENT_WRITE;
        }
        if (path.startsWith("/api/v1/catalog")
                || path.startsWith("/api/v1/standard-")
                || path.startsWith("/api/v1/data-sources")) {
            return isRead(method) ? IrcsPermissions.CATALOG_READ : IrcsPermissions.CATALOG_WRITE;
        }
        if (path.startsWith("/api/v1/collection-tasks") || path.startsWith("/api/v1/media-request-batches")) {
            return isTaskRun(path, method)
                    ? IrcsPermissions.TASK_RUN
                    : isRead(method) ? IrcsPermissions.TASK_READ : IrcsPermissions.TASK_WRITE;
        }
        if (path.startsWith("/api/v1/dashboard")
                || path.startsWith("/api/v1/ops-alert")
                || path.startsWith("/api/v1/ops")
                || path.startsWith("/api/v1/debug")) {
            return isRead(method) ? IrcsPermissions.OPS_READ : IrcsPermissions.OPS_RUN;
        }
        if (path.startsWith("/api/v1/cover-images") || path.startsWith("/media")) {
            return isRead(method) ? IrcsPermissions.STORAGE_READ : IrcsPermissions.STORAGE_WRITE;
        }
        if (path.startsWith("/api/v1/configs")) {
            return isRead(method) ? IrcsPermissions.CONFIG_READ : IrcsPermissions.CONFIG_WRITE;
        }
        if (path.startsWith("/api/v1/common")) {
            return IrcsPermissions.CONFIG_READ;
        }
        if (path.startsWith("/api/v1/credentials")) {
            return isRead(method) ? IrcsPermissions.CREDENTIAL_READ : IrcsPermissions.CREDENTIAL_WRITE;
        }
        if (path.startsWith("/api/v1/magnet-providers")) {
            return isRead(method) ? IrcsPermissions.MAGNET_READ : IrcsPermissions.MAGNET_WRITE;
        }
        if (path.startsWith("/api/v1/magnets/search")) {
            return IrcsPermissions.MAGNET_RUN;
        }
        if (path.startsWith("/api/v1/magnets")) {
            return isRead(method) ? IrcsPermissions.MAGNET_READ : IrcsPermissions.MAGNET_WRITE;
        }
        if (path.startsWith("/api/v1/scraper/manual")) {
            return IrcsPermissions.SCRAPER_RUN;
        }
        if (path.startsWith("/api/v1/messages")) {
            return isRead(method) ? IrcsPermissions.INTERACTION_READ : IrcsPermissions.INTERACTION_MODERATE;
        }
        return IrcsPermissions.ADMIN_ACCESS;
    }

    private static boolean isRead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static boolean isTaskRun(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return path.endsWith("/start")
                || path.endsWith("/resume")
                || path.endsWith("/pause")
                || path.endsWith("/stop")
                || path.endsWith("/cancel");
    }

    private static void writeAuthError(
            HttpServletRequest request,
            HttpServletResponse response,
            IrcsAuthException exception) throws IOException {
        HttpStatus status = exception.reason() == IrcsAuthException.Reason.FORBIDDEN
                ? HttpStatus.FORBIDDEN
                : HttpStatus.UNAUTHORIZED;
        ApiErrorResponses.write(
                request,
                response,
                status,
                "auth.token." + exception.reason().name().toLowerCase(Locale.ROOT),
                exception.getMessage(),
                "auth");
    }
}
