package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.security.IrcsAuthClaims;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;

class MemberTokenServiceTest {

    private static final String SECRET = "test-identity-jwt-secret-change-me-32-bytes";
    private static final String ENV_SECRET = "env-identity-jwt-secret-change-me-32-bytes!";

    private final SystemConfigRepository systemConfigRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final JdbcInteractionRepository interactionRepository = org.mockito.Mockito.mock(JdbcInteractionRepository.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final MemberTokenService service =
            new MemberTokenService(systemConfigRepository, interactionRepository, environment);

    @Test
    void acceptsIdentityMemberToken() {
        UUID memberId = UUID.randomUUID();
        configureJwt();
        when(interactionRepository.findMemberStatus(memberId)).thenReturn(Optional.of("ACTIVE"));

        UUID resolved = service.requireMemberId("Bearer " + token(memberId, "ROLE_MEMBER"));

        assertEquals(memberId, resolved);
    }

    @Test
    void rejectsMissingToken() {
        ApiException exception = assertThrows(ApiException.class, () -> service.requireMemberId(null));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void rejectsBannedMember() {
        UUID memberId = UUID.randomUUID();
        configureJwt();
        when(interactionRepository.findMemberStatus(memberId)).thenReturn(Optional.of("BANNED"));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.requireMemberId("Bearer " + token(memberId, "ROLE_MEMBER")));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
    }

    @Test
    void rejectsNonMemberRole() {
        UUID memberId = UUID.randomUUID();
        configureJwt();

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.requireMemberId("Bearer " + token(memberId, "ROLE_ADMIN")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void rejectsPreRbacRoleOnlyToken() {
        UUID memberId = UUID.randomUUID();
        configureJwt();

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.requireMemberId("Bearer " + roleOnlyToken(memberId)));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void injectedJwtSecretOverridesStoredConfig() {
        UUID memberId = UUID.randomUUID();
        environment.setProperty("app.identity.jwt.secret", ENV_SECRET);
        environment.setProperty("app.identity.jwt.iat-floor", "0");
        when(systemConfigRepository.findValue("security.jwt.secret")).thenReturn(Optional.of(SECRET));
        when(systemConfigRepository.findValue("security.jwt.iat-floor")).thenReturn(Optional.empty());
        when(interactionRepository.findMemberStatus(memberId)).thenReturn(Optional.of("ACTIVE"));

        UUID resolved = service.requireMemberId("Bearer " + token(memberId, "ROLE_MEMBER", ENV_SECRET));

        assertEquals(memberId, resolved);
    }

    @Test
    void k8sJwtSecretOverridesStoredConfig() {
        UUID memberId = UUID.randomUUID();
        environment.setProperty("APP_IDENTITY_JWT_SECRET", ENV_SECRET);
        when(systemConfigRepository.findValue("security.jwt.secret")).thenReturn(Optional.of(SECRET));
        when(systemConfigRepository.findValue("security.jwt.iat-floor")).thenReturn(Optional.empty());
        when(interactionRepository.findMemberStatus(memberId)).thenReturn(Optional.of("ACTIVE"));

        UUID resolved = service.requireMemberId("Bearer " + token(memberId, "ROLE_MEMBER", ENV_SECRET));

        assertEquals(memberId, resolved);
    }

    @Test
    void kubernetesSecretPropertySourceOverridesStoredJwtSecret() {
        UUID memberId = UUID.randomUUID();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Kubernetes Secret ircs-dev/runtime",
                java.util.Map.of("app.identity.jwt.secret", ENV_SECRET)));
        when(systemConfigRepository.findValue("security.jwt.secret")).thenReturn(Optional.of(SECRET));
        when(systemConfigRepository.findValue("security.jwt.iat-floor")).thenReturn(Optional.empty());
        when(interactionRepository.findMemberStatus(memberId)).thenReturn(Optional.of("ACTIVE"));

        UUID resolved = service.requireMemberId("Bearer " + token(memberId, "ROLE_MEMBER", ENV_SECRET));

        assertEquals(memberId, resolved);
    }

    @Test
    void applicationConfigDefaultDoesNotOverrideStoredJwtSecret() {
        UUID memberId = UUID.randomUUID();
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
                java.util.Map.of("app.identity.jwt.secret", ENV_SECRET)));
        when(systemConfigRepository.findValue("security.jwt.secret")).thenReturn(Optional.of(SECRET));
        when(systemConfigRepository.findValue("security.jwt.iat-floor")).thenReturn(Optional.empty());
        when(interactionRepository.findMemberStatus(memberId)).thenReturn(Optional.of("ACTIVE"));

        UUID resolved = service.requireMemberId("Bearer " + token(memberId, "ROLE_MEMBER", SECRET));

        assertEquals(memberId, resolved);
    }

    private void configureJwt() {
        when(systemConfigRepository.findValue("security.jwt.secret")).thenReturn(Optional.of(SECRET));
        when(systemConfigRepository.findValue("security.jwt.iat-floor")).thenReturn(Optional.empty());
    }

    private String token(UUID memberId, String role) {
        return token(memberId, role, SECRET);
    }

    private String token(UUID memberId, String role, String secret) {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(memberId.toString())
                .claim(IrcsAuthClaims.ROLE, role)
                .claim(IrcsAuthClaims.PERMISSIONS, permissions(role))
                .claim(IrcsAuthClaims.SCOPES, scopes(role))
                .claim(IrcsAuthClaims.DATA_CATEGORIES, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.DATA_GENRES, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.DATA_TAGS, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.CONTENT_VISIBILITY, IrcsPermissions.defaultContentVisibility(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private String roleOnlyToken(UUID memberId) {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(memberId.toString())
                .claim(IrcsAuthClaims.ROLE, IrcsPermissions.ROLE_MEMBER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private Set<String> permissions(String role) {
        return IrcsPermissions.ROLE_ADMIN.equals(role)
                ? Set.of(IrcsPermissions.ALL)
                : IrcsPermissions.defaultPermissions(role);
    }

    private Set<String> scopes(String role) {
        return IrcsPermissions.ROLE_ADMIN.equals(role)
                ? Set.of(IrcsPermissions.SCOPE_ADMIN_ALL, IrcsPermissions.SCOPE_DATA_ALL)
                : IrcsPermissions.defaultScopes(role);
    }
}
