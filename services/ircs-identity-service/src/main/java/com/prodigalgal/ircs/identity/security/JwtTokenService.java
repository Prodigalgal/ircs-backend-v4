package com.prodigalgal.ircs.identity.security;





import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.application.IdentityConfigService;
import com.prodigalgal.ircs.common.security.IrcsAuthClaims;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private static final int MIN_HMAC_SECRET_BYTES = 32;

    private final IdentityConfigService configService;

    public String generateMemberToken(MemberRecord member) {
        long now = System.currentTimeMillis();
        long expiry = now + jwtTtl().toMillis();
        return Jwts.builder()
                .subject(member.id().toString())
                .claim(IrcsAuthClaims.ROLE, IrcsPermissions.ROLE_MEMBER)
                .claim(IrcsAuthClaims.PERMISSIONS, IrcsPermissions.defaultPermissions(IrcsPermissions.ROLE_MEMBER))
                .claim(IrcsAuthClaims.SCOPES, IrcsPermissions.defaultScopes(IrcsPermissions.ROLE_MEMBER))
                .claim(IrcsAuthClaims.DATA_CATEGORIES, IrcsPermissions.memberCategoryScope(member.adultContentAllowed()))
                .claim(IrcsAuthClaims.DATA_GENRES, IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_MEMBER))
                .claim(IrcsAuthClaims.DATA_TAGS, IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_MEMBER))
                .claim(IrcsAuthClaims.CONTENT_VISIBILITY, IrcsPermissions.defaultContentVisibility(IrcsPermissions.ROLE_MEMBER))
                .issuedAt(new Date(now))
                .expiration(new Date(expiry))
                .signWith(secretKey())
                .compact();
    }

    public String generateAdminToken(String username) {
        long now = System.currentTimeMillis();
        long expiry = now + adminJwtTtl().toMillis();
        return Jwts.builder()
                .subject(username)
                .claim(IrcsAuthClaims.ROLE, IrcsPermissions.ROLE_ADMIN)
                .claim(IrcsAuthClaims.PERMISSIONS, IrcsPermissions.defaultPermissions(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.SCOPES, IrcsPermissions.defaultScopes(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.DATA_CATEGORIES, IrcsPermissions.defaultCategoryScope(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.DATA_GENRES, IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.DATA_TAGS, IrcsPermissions.defaultDataScope(IrcsPermissions.ROLE_ADMIN))
                .claim(IrcsAuthClaims.CONTENT_VISIBILITY, IrcsPermissions.defaultContentVisibility(IrcsPermissions.ROLE_ADMIN))
                .issuedAt(new Date(now))
                .expiration(new Date(expiry))
                .signWith(secretKey())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long iatFloorSeconds() {
        return configService.longValue(IdentityConfigKey.JWT_IAT_FLOOR);
    }

    private SecretKey secretKey() {
        String secret = configService.value(IdentityConfigKey.JWT_SECRET);
        if (!StringUtils.hasText(secret)) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "JWT secret is not configured",
                    "auth",
                    "jwt.secret.missing");
        }
        byte[] bytes = secret.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_HMAC_SECRET_BYTES) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "JWT secret is too short",
                    "auth",
                    "jwt.secret.weak");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    private Duration jwtTtl() {
        Duration ttl = configService.durationProperty("app.identity.jwt.ttl", Duration.ofDays(7));
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(7) : ttl;
    }

    private Duration adminJwtTtl() {
        Duration ttl = configService.durationProperty("app.identity.admin-jwt.ttl", Duration.ofDays(1));
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(1) : ttl;
    }
}
