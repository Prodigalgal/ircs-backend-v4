package com.prodigalgal.ircs.apigateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.common.web.CommonApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminApiTokenControllerTest {

    @Test
    void listsTokenMetadataWithoutRawToken() throws Exception {
        AdminApiTokenService tokenService = mock(AdminApiTokenService.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(any(HttpServletRequest.class), eq(IrcsPermissions.ROLE_ADMIN), eq(IrcsPermissions.ADMIN_ACCESS)))
                .thenReturn(adminPrincipal());
        when(tokenService.list()).thenReturn(List.of(new AdminApiTokenDtos.Summary(
                UUID.fromString("019ec176-0619-7000-8000-000000000001"),
                "deploy",
                "ircs_pat_abcd",
                "ACTIVE",
                "admin",
                Instant.parse("2026-06-19T10:00:00Z"),
                null,
                null,
                null,
                null)));

        mvc(tokenService, authenticator)
                .perform(get("/api/v1/auth/api-tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenPrefix").value("ircs_pat_abcd"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
    }

    @Test
    void createsTokenAndReturnsRawTokenOnce() throws Exception {
        AdminApiTokenService tokenService = mock(AdminApiTokenService.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(any(HttpServletRequest.class), eq(IrcsPermissions.ROLE_ADMIN), eq(IrcsPermissions.ADMIN_ACCESS)))
                .thenReturn(adminPrincipal());
        when(tokenService.create("deploy", "admin")).thenReturn(new AdminApiTokenDtos.CreatedResponse(
                UUID.fromString("019ec176-0619-7000-8000-000000000002"),
                "deploy",
                "ircs_pat_abcd",
                "ircs_pat_abcd_secret",
                Instant.parse("2026-06-19T10:00:00Z")));

        mvc(tokenService, authenticator)
                .perform(post("/api/v1/auth/api-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"deploy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("ircs_pat_abcd_secret"));
    }

    @Test
    void revokesToken() throws Exception {
        AdminApiTokenService tokenService = mock(AdminApiTokenService.class);
        ApiGatewayAuthenticator authenticator = mock(ApiGatewayAuthenticator.class);
        when(authenticator.requireRoleAndPermission(any(HttpServletRequest.class), eq(IrcsPermissions.ROLE_ADMIN), eq(IrcsPermissions.ADMIN_ACCESS)))
                .thenReturn(adminPrincipal());
        UUID id = UUID.fromString("019ec176-0619-7000-8000-000000000003");

        mvc(tokenService, authenticator)
                .perform(delete("/api/v1/auth/api-tokens/{id}", id))
                .andExpect(status().isNoContent());

        verify(tokenService).revoke(id, "admin");
    }

    private static MockMvc mvc(AdminApiTokenService tokenService, ApiGatewayAuthenticator authenticator) {
        return MockMvcBuilders
                .standaloneSetup(new AdminApiTokenController(tokenService, authenticator))
                .setControllerAdvice(new CommonApiExceptionHandler())
                .build();
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
}
