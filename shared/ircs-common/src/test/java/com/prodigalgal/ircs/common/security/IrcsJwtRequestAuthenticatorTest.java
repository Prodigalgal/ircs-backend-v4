package com.prodigalgal.ircs.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IrcsJwtRequestAuthenticatorTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void authenticatesQueryTokenForNativeEventSource() {
        IrcsJwtRequestAuthenticator authenticator = new IrcsJwtRequestAuthenticator(
                new StaticConfigResolver(SECRET, 0));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/stream/metrics");
        request.addHeader("Accept", "text/event-stream");
        request.setParameter("token", token());

        IrcsRequestPrincipal principal = authenticator.authenticateIfPresent(request).orElseThrow();

        assertThat(principal.subject()).isEqualTo("admin");
        assertThat(principal.role()).isEqualTo(IrcsPermissions.ROLE_ADMIN);
        assertThat(principal.hasPermission(IrcsPermissions.OPS_READ)).isTrue();
    }

    @Test
    void ignoresQueryTokenOnRegularRequests() {
        IrcsJwtRequestAuthenticator authenticator = new IrcsJwtRequestAuthenticator(
                new StaticConfigResolver(SECRET, 0));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/whatever");
        request.setParameter("token", token());

        assertThat(authenticator.authenticateIfPresent(request)).isEmpty();
    }

    private static String token() {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("admin")
                .claim(IrcsAuthClaims.ROLE, IrcsPermissions.ROLE_ADMIN)
                .claim(IrcsAuthClaims.PERMISSIONS, IrcsPermissions.defaultPermissions(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.SCOPES, IrcsPermissions.defaultScopes(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.DATA_CATEGORIES, IrcsPermissions.defaultCategoryScope(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.DATA_GENRES, IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.DATA_TAGS, IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.CONTENT_VISIBILITY, IrcsPermissions.defaultContentVisibility(IrcsPermissions.ROLE_ADMIN))
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static final class StaticConfigResolver extends IrcsJwtRuntimeConfigResolver {

        private final String secret;
        private final long iatFloorSeconds;

        private StaticConfigResolver(String secret, long iatFloorSeconds) {
            super(null, null, null, null);
            this.secret = secret;
            this.iatFloorSeconds = iatFloorSeconds;
        }

        @Override
        public String jwtSecret() {
            return secret;
        }

        @Override
        public long iatFloorSeconds() {
            return iatFloorSeconds;
        }
    }
}
