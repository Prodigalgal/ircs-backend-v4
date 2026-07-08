package com.prodigalgal.ircs.identity.application;








import com.prodigalgal.ircs.identity.messaging.MailPublisher;
import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.security.JwtTokenService;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.security.IrcsAuthClaims;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberTokenResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWVerification;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class MemberAuthServiceTest {

    private static final String SECRET = "test-identity-jwt-secret-change-me-32-bytes";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final IdentityConfigService configService = mock(IdentityConfigService.class);
    private final PoWService poWService = mock(PoWService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MailPublisher mailPublisher = mock(MailPublisher.class);
    private final MemberStatusCacheService statusCacheService = mock(MemberStatusCacheService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private MemberAuthService authService;

    @BeforeEach
    void setUp() {
        JwtTokenService jwtTokenService = new JwtTokenService(configService);
        authService = new MemberAuthService(
                memberRepository,
                passwordEncoder,
                configService,
                poWService,
                redisTemplate,
                mailPublisher,
                statusCacheService,
                jwtTokenService,
                new MemberResponseMapper());
        when(configService.value(IdentityConfigKey.JWT_SECRET)).thenReturn(SECRET);
        when(configService.durationProperty("app.identity.jwt.ttl", Duration.ofDays(7))).thenReturn(Duration.ofDays(7));
        when(configService.bool(IdentityConfigKey.MEMBER_REGISTER_EMAIL_VERIFY_ENABLED)).thenReturn(true);
    }

    @Test
    void loginReturnsRbacMemberToken() {
        UUID memberId = UUID.randomUUID();
        MemberRecord member = member(memberId, MemberStatus.ACTIVE);
        when(memberRepository.findByEmail("codex@example.com")).thenReturn(Optional.of(member));

        MemberTokenResponse response = authService.login(new MemberLoginRequest(
                "CODEX@example.com",
                "Pass123",
                new PoWVerification("challenge-id", "nonce")));

        assertEquals(memberId.toString(), response.id());
        Claims claims = parseClaims(response.token());
        assertEquals(IrcsPermissions.ROLE_MEMBER, claims.get(IrcsAuthClaims.ROLE, String.class));
        assertEquals(memberId.toString(), claims.getSubject());
        assertTrue(claimSet(claims, IrcsAuthClaims.PERMISSIONS).contains(IrcsPermissions.PORTAL_READ));
        assertTrue(claimSet(claims, IrcsAuthClaims.SCOPES).contains(IrcsPermissions.SCOPE_PORTAL_READ));
        assertTrue(claimSet(claims, IrcsAuthClaims.DATA_CATEGORIES).contains("movie"));
        assertTrue(!claimSet(claims, IrcsAuthClaims.DATA_CATEGORIES).contains("adult"));
        assertTrue(claimSet(claims, IrcsAuthClaims.CONTENT_VISIBILITY).contains(IrcsPermissions.VISIBILITY_MEMBER));
        verify(poWService).verifyIfPresent(new PoWVerification("challenge-id", "nonce"));
        verify(statusCacheService).updateStatus(memberId, MemberStatus.ACTIVE);
    }

    @Test
    void loginIncludesAdultCategoryWhenMemberIsAllowed() {
        UUID memberId = UUID.randomUUID();
        MemberRecord member = member(memberId, MemberStatus.ACTIVE).withAdultContentAllowed(true);
        when(memberRepository.findByEmail("codex@example.com")).thenReturn(Optional.of(member));

        MemberTokenResponse response = authService.login(new MemberLoginRequest(
                "codex@example.com",
                "Pass123",
                new PoWVerification("challenge-id", "nonce")));

        Claims claims = parseClaims(response.token());

        assertTrue(claimSet(claims, IrcsAuthClaims.DATA_CATEGORIES).contains("adult"));
    }

    @Test
    void loginRejectsBannedMember() {
        when(memberRepository.findByEmail("codex@example.com"))
                .thenReturn(Optional.of(member(UUID.randomUUID(), MemberStatus.BANNED)));

        ApiException ex = assertThrows(ApiException.class, () -> authService.login(new MemberLoginRequest(
                "codex@example.com",
                "Pass123",
                new PoWVerification("challenge-id", "nonce"))));

        assertEquals("auth.banned", ex.errorKey());
    }

    private MemberRecord member(UUID id, MemberStatus status) {
        Instant now = Instant.parse("2026-06-04T00:00:00Z");
        return new MemberRecord(
                id,
                now,
                now,
                0L,
                "codex@example.com",
                passwordEncoder.encode("Pass123"),
                "Codex",
                "https://i.pravatar.cc/300?u=codex@example.com",
                "MEMBER",
                status,
                false,
                0,
                0,
                null,
                0);
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
