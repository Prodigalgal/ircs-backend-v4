package com.prodigalgal.ircs.apigateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.security.IrcsAuthException;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.common.web.CommonApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiGatewayAuthGateTest {

    @Test
    void rejectsProtectedAdminRouteWithoutToken() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.ADMIN_MEMBERS_READ)))
                .thenThrow(new IrcsAuthException(IrcsAuthException.Reason.MISSING, "缺少登录凭证"));

        mvc(proxyClient, authenticator)
                .perform(get("/api/v1/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.token.missing"));

        verify(proxyClient, never()).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void permitsAdminLoginWithoutToken() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.authenticateIfPresent(any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mvc(proxyClient, authenticator)
                .perform(post("/api/v1/auth/login"))
                .andExpect(status().isOk());

        verify(authenticator, never()).requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                any(String.class));
        verify(proxyClient).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void protectsAdminPasswordChange() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.ADMIN_AUTH_CHANGE_PASSWORD)))
                .thenReturn(adminPrincipal());

        mvc(proxyClient, authenticator)
                .perform(post("/api/v1/auth/change-password"))
                .andExpect(status().isOk());

        verify(authenticator).requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.ADMIN_AUTH_CHANGE_PASSWORD));
        verify(proxyClient).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void rejectsAdminRouteWhenPermissionMissing() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.CONTENT_WRITE)))
                .thenThrow(new IrcsAuthException(IrcsAuthException.Reason.FORBIDDEN, "权限不足"));

        mvc(proxyClient, authenticator)
                .perform(post("/api/v1/unified-videos"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.token.forbidden"));

        verify(proxyClient, never()).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void permitsProtectedAdminRouteWithApiTokenPrincipal() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.OPS_READ)))
                .thenReturn(apiTokenPrincipal());

        mvc(proxyClient, authenticator)
                .perform(get("/api/v1/dashboard/metrics")
                        .header(AdminApiTokenService.HEADER_API_TOKEN, "ircs_pat_test"))
                .andExpect(status().isOk());

        verify(authenticator).requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.OPS_READ));
        verify(proxyClient).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void rejectsProtectedAdminRouteWithInvalidApiToken() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.ROLE_ADMIN),
                eq(IrcsPermissions.OPS_READ)))
                .thenThrow(new IrcsAuthException(IrcsAuthException.Reason.INVALID, "API Token 无效或已撤销"));

        mvc(proxyClient, authenticator)
                .perform(get("/api/v1/dashboard/metrics")
                        .header("Authorization", "Bearer ircs_pat_invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.token.invalid"));

        verify(proxyClient, never()).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void protectsPortalInteractionWritesWithPortalPermission() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requirePermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.PORTAL_INTERACTION_WRITE)))
                .thenReturn(memberPrincipal());

        mvc(proxyClient, authenticator)
                .perform(post("/api/portal/interaction/history"))
                .andExpect(status().isOk());

        verify(authenticator).requirePermission(
                any(HttpServletRequest.class),
                eq(IrcsPermissions.PORTAL_INTERACTION_WRITE));
        verify(proxyClient).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    @Test
    void doesNotProxyUnknownApiPrefix() throws Exception {
        GatewayProxyClient proxyClient = mock(GatewayProxyClient.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);

        mvc(proxyClient, authenticator)
                .perform(post("/api/unknown/portal/interaction/history"))
                .andExpect(status().isNotFound());

        verify(proxyClient, never()).proxy(any(HttpServletRequest.class), any(HttpServletResponse.class), any(ApiGatewayRoutes.class));
    }

    private static MockMvc mvc(GatewayProxyClient proxyClient, ApiGatewayAuthenticator authenticator) {
        return MockMvcBuilders
                .standaloneSetup(new ApiGatewayProxyController(proxyClient, routes(), authenticator))
                .setControllerAdvice(new CommonApiExceptionHandler())
                .build();
    }

    private static ApiGatewayRoutes routes() {
        return new ApiGatewayRoutes(
                "http://identity",
                "http://content",
                "http://catalog",
                "http://aggregation",
                "http://task",
                "http://ops",
                "http://ops-alert",
                "http://portal",
                "http://search",
                "http://storage",
                "http://config",
                "http://credential",
                "http://magnet",
                "http://scraper",
                "http://interaction");
    }

    private static IrcsRequestPrincipal adminPrincipal() {
        return new IrcsRequestPrincipal(
                "admin",
                IrcsPermissions.ROLE_ADMIN,
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.SCOPE_ADMIN_ALL, IrcsPermissions.SCOPE_DATA_ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL));
    }

    private static IrcsRequestPrincipal apiTokenPrincipal() {
        return new IrcsRequestPrincipal(
                "api-token:ircs_pat_test",
                IrcsPermissions.ROLE_ADMIN,
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.SCOPE_ADMIN_ALL, IrcsPermissions.SCOPE_DATA_ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL));
    }

    private static IrcsRequestPrincipal memberPrincipal() {
        return new IrcsRequestPrincipal(
                "member-1",
                IrcsPermissions.ROLE_MEMBER,
                Set.of(IrcsPermissions.PORTAL_INTERACTION_WRITE),
                Set.of(IrcsPermissions.SCOPE_INTERACTION_WRITE),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.ALL),
                Set.of(IrcsPermissions.VISIBILITY_PUBLIC, IrcsPermissions.VISIBILITY_MEMBER));
    }
}
