package com.prodigalgal.ircs.identity.application;




import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.security.JwtTokenService;
import com.prodigalgal.ircs.identity.api.ApiException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.security.IrcsAuthClaims;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminPasswordChangeRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminTokenResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWVerification;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminAuthServiceTest {

    private static final String SECRET = "test-identity-jwt-secret-change-me-32-bytes";

    private final IdentityConfigService configService = mock(IdentityConfigService.class);
    private final PoWService poWService = mock(PoWService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AdminAuthService authService;

    @BeforeEach
    void setUp() {
        JwtTokenService jwtTokenService = new JwtTokenService(configService);
        authService = new AdminAuthService(configService, passwordEncoder, poWService, jwtTokenService);
        when(configService.value(IdentityConfigKey.JWT_SECRET)).thenReturn(SECRET);
        when(configService.durationProperty("app.identity.admin-jwt.ttl", Duration.ofDays(1)))
                .thenReturn(Duration.ofDays(1));
        when(configService.value(IdentityConfigKey.ADMIN_USERNAME)).thenReturn("admin");
        when(configService.value(IdentityConfigKey.ADMIN_PASSWORD)).thenReturn(passwordEncoder.encode("Admin123"));
        when(configService.hasInjectedValue(IdentityConfigKey.ADMIN_PASSWORD)).thenReturn(false);
    }

    @Test
    void loginReturnsRbacAdminToken() {
        AdminTokenResponse response = authService.login(new AdminLoginRequest(
                "admin",
                "Admin123",
                new PoWVerification("challenge-id", "nonce")));

        Claims claims = parseClaims(response.token());
        assertEquals("admin", claims.getSubject());
        assertEquals(IrcsPermissions.ROLE_ADMIN, claims.get(IrcsAuthClaims.ROLE, String.class));
        assertTrue(claimSet(claims, IrcsAuthClaims.PERMISSIONS).contains(IrcsPermissions.ALL));
        assertTrue(claimSet(claims, IrcsAuthClaims.SCOPES).contains(IrcsPermissions.SCOPE_ADMIN_ALL));
        assertTrue(claimSet(claims, IrcsAuthClaims.DATA_CATEGORIES).contains(IrcsPermissions.ALL));
        assertTrue(claimSet(claims, IrcsAuthClaims.CONTENT_VISIBILITY).contains(IrcsPermissions.ALL));
        verify(poWService).verifyIfPresent(new PoWVerification("challenge-id", "nonce"));
    }

    @Test
    void loginRejectsInvalidPassword() {
        ApiException ex = assertThrows(ApiException.class, () -> authService.login(new AdminLoginRequest(
                "admin",
                "wrong",
                new PoWVerification("challenge-id", "nonce"))));

        assertEquals("auth.failed", ex.errorKey());
    }

    @Test
    void changePasswordUpdatesPasswordHashAndIatFloor() {
        authService.changePassword(new AdminPasswordChangeRequest("Admin123", "Next123"));

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).updateValue(org.mockito.Mockito.eq(IdentityConfigKey.ADMIN_PASSWORD), passwordCaptor.capture());
        verify(configService).updateValue(org.mockito.Mockito.eq(IdentityConfigKey.JWT_IAT_FLOOR), anyString());
        assertTrue(passwordEncoder.matches("Next123", passwordCaptor.getValue()));
    }

    @Test
    void changePasswordRejectsInvalidOldPassword() {
        ApiException ex = assertThrows(ApiException.class, () -> authService.changePassword(
                new AdminPasswordChangeRequest("wrong", "Next123")));

        assertEquals("password.invalid", ex.errorKey());
    }

    @Test
    void changePasswordRejectsWhenAdminPasswordIsInjected() {
        when(configService.hasInjectedValue(IdentityConfigKey.ADMIN_PASSWORD)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> authService.changePassword(
                new AdminPasswordChangeRequest("Admin123", "Next123")));

        assertEquals("password.injected", ex.errorKey());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private java.util.Set<String> claimSet(Claims claims, String name) {
        Object raw = claims.get(name);
        if (raw instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(java.util.stream.Collectors.toSet());
        }
        return java.util.Set.of();
    }
}
