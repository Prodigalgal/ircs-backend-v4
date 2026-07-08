package com.prodigalgal.ircs.apigateway;

import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/api-tokens")
class AdminApiTokenController {

    private final AdminApiTokenService tokenService;
    private final ApiGatewayAuthenticator authenticator;

    AdminApiTokenController(
            AdminApiTokenService tokenService,
            ApiGatewayAuthenticator authenticator) {
        this.tokenService = tokenService;
        this.authenticator = authenticator;
    }

    @GetMapping
    List<AdminApiTokenDtos.Summary> list(HttpServletRequest request) {
        requireAdmin(request);
        return tokenService.list();
    }

    @PostMapping
    AdminApiTokenDtos.CreatedResponse create(
            @Valid @RequestBody AdminApiTokenDtos.CreateRequest body,
            HttpServletRequest request) {
        IrcsRequestPrincipal principal = requireAdmin(request);
        return tokenService.create(body.name(), principal.subject());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@PathVariable(name = "request") UUID id, HttpServletRequest request) {
        IrcsRequestPrincipal principal = requireAdmin(request);
        tokenService.revoke(id, principal.subject());
        return ResponseEntity.noContent().build();
    }

    private IrcsRequestPrincipal requireAdmin(HttpServletRequest request) {
        return authenticator.requireRoleAndPermission(
                request,
                IrcsPermissions.ROLE_ADMIN,
                IrcsPermissions.ADMIN_ACCESS);
    }
}
