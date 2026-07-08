package com.prodigalgal.ircs.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IrcsJwtAuthDecoderTest {

    private static final String SECRET = "test-identity-jwt-secret-change-me-32-bytes!!";
    private final IrcsJwtAuthDecoder decoder = new IrcsJwtAuthDecoder();

    @Test
    void decodesRequiredRbacClaims() {
        String token = Jwts.builder()
                .subject("admin")
                .claim(IrcsAuthClaims.ROLE, IrcsPermissions.ROLE_ADMIN)
                .claim(IrcsAuthClaims.PERMISSIONS, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.SCOPES, Set.of(IrcsPermissions.SCOPE_ADMIN_ALL, IrcsPermissions.SCOPE_DATA_ALL))
                .claim(IrcsAuthClaims.DATA_CATEGORIES, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.DATA_GENRES, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.DATA_TAGS, Set.of(IrcsPermissions.ALL))
                .claim(IrcsAuthClaims.CONTENT_VISIBILITY, Set.of(IrcsPermissions.ALL))
                .issuedAt(new Date())
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        IrcsRequestPrincipal principal = decoder.decode(token, SECRET, 0);

        assertThat(principal.subject()).isEqualTo("admin");
        assertThat(principal.role()).isEqualTo(IrcsPermissions.ROLE_ADMIN);
        assertThat(principal.permissions()).contains(IrcsPermissions.ALL);
        assertThat(principal.scopes()).contains(IrcsPermissions.SCOPE_ADMIN_ALL, IrcsPermissions.SCOPE_DATA_ALL);
    }

    @Test
    void rejectsPreRbacTokenWithoutPermissionClaims() {
        String token = Jwts.builder()
                .subject("admin")
                .claim(IrcsAuthClaims.ROLE, IrcsPermissions.ROLE_ADMIN)
                .issuedAt(new Date())
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> decoder.decode(token, SECRET, 0))
                .isInstanceOf(IrcsAuthException.class)
                .hasMessageContaining(IrcsAuthClaims.PERMISSIONS);
    }
}
