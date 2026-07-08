package com.prodigalgal.ircs.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.util.StringUtils;

public class IrcsJwtAuthDecoder {

    private static final int MIN_HMAC_SECRET_BYTES = 32;

    public IrcsRequestPrincipal decode(String token, String secret, long iatFloorSeconds) {
        if (!StringUtils.hasText(token)) {
            throw new IrcsAuthException(IrcsAuthException.Reason.MISSING, "Missing token");
        }
        if (!StringUtils.hasText(secret)) {
            throw new IrcsAuthException(IrcsAuthException.Reason.INVALID, "JWT secret is not configured");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey(secret))
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
            Date issuedAt = claims.getIssuedAt();
            if (issuedAt != null && issuedAt.getTime() / 1000 < iatFloorSeconds) {
                throw new IrcsAuthException(IrcsAuthException.Reason.STALE, "Token has been revoked");
            }
            String subject = claims.getSubject();
            if (!StringUtils.hasText(subject)) {
                throw new IrcsAuthException(IrcsAuthException.Reason.INVALID, "Token subject is missing");
            }
            String roleClaim = claims.get(IrcsAuthClaims.ROLE, String.class);
            if (!StringUtils.hasText(roleClaim)) {
                throw new IrcsAuthException(IrcsAuthException.Reason.INVALID, "Token role is missing");
            }
            String role = IrcsPermissions.normalizeRole(roleClaim);
            Set<String> permissions = claimSet(claims.get(IrcsAuthClaims.PERMISSIONS));
            Set<String> scopes = claimSet(claims.get(IrcsAuthClaims.SCOPES));
            Set<String> visibility = claimSet(claims.get(IrcsAuthClaims.CONTENT_VISIBILITY));
            Set<String> categories = claimSet(claims.get(IrcsAuthClaims.DATA_CATEGORIES));
            Set<String> genres = claimSet(claims.get(IrcsAuthClaims.DATA_GENRES));
            Set<String> tags = claimSet(claims.get(IrcsAuthClaims.DATA_TAGS));
            requireClaim(permissions, IrcsAuthClaims.PERMISSIONS);
            requireClaim(scopes, IrcsAuthClaims.SCOPES);
            requireClaim(visibility, IrcsAuthClaims.CONTENT_VISIBILITY);
            requireClaim(categories, IrcsAuthClaims.DATA_CATEGORIES);
            requireClaim(genres, IrcsAuthClaims.DATA_GENRES);
            requireClaim(tags, IrcsAuthClaims.DATA_TAGS);
            return new IrcsRequestPrincipal(
                    subject.trim(),
                    role,
                    permissions,
                    scopes,
                    categories,
                    genres,
                    tags,
                    visibility);
        } catch (ExpiredJwtException ex) {
            throw new IrcsAuthException(IrcsAuthException.Reason.EXPIRED, "Token has expired");
        } catch (IrcsAuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IrcsAuthException(IrcsAuthException.Reason.INVALID, "Invalid token");
        }
    }

    private static SecretKey secretKey(String secret) {
        byte[] bytes = secret.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_HMAC_SECRET_BYTES) {
            throw new IrcsAuthException(IrcsAuthException.Reason.INVALID, "JWT secret is too short");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    private static Set<String> claimSet(Object raw) {
        if (raw instanceof Collection<?> values) {
            return IrcsRequestPrincipal.normalizeSet(values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList());
        }
        if (raw instanceof String value) {
            return IrcsRequestPrincipal.parseCsv(value);
        }
        return Set.of();
    }

    private static void requireClaim(Set<String> values, String claimName) {
        if (values.isEmpty()) {
            throw new IrcsAuthException(IrcsAuthException.Reason.INVALID, "Token claim is missing: " + claimName);
        }
    }
}
