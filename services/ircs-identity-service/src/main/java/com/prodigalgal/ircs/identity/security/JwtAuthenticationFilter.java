package com.prodigalgal.ircs.identity.security;





import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.application.MemberStatusCacheService;
import com.prodigalgal.ircs.identity.application.IdentityConfigService;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import com.prodigalgal.ircs.common.security.IrcsAuthClaims;
import com.prodigalgal.ircs.common.security.IrcsAuthHeaders;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final MemberStatusCacheService memberStatusCacheService;
    private final IdentityConfigService configService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (authenticateTrustedHeaders(request, response, filterChain)) {
            return;
        }

        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtTokenService.parse(token);
            Date issuedAt = claims.getIssuedAt();
            long iatFloor = jwtTokenService.iatFloorSeconds();
            if (issuedAt != null && issuedAt.getTime() / 1000 < iatFloor) {
                sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "登录凭证已失效，请重新登录");
                return;
            }

            String subject = claims.getSubject();
            String rawRole = claims.get(IrcsAuthClaims.ROLE, String.class);
            if (!StringUtils.hasText(rawRole)) {
                sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的身份标识");
                return;
            }
            String role = IrcsPermissions.normalizeRole(rawRole);
            if (!StringUtils.hasText(subject)) {
                sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的身份标识");
                return;
            }
            validateRequiredRbacClaims(claims);

            List<SimpleGrantedAuthority> authorities;
            if (IrcsPermissions.ROLE_MEMBER.equals(role)) {
                UUID memberId = UUID.fromString(subject);
                MemberStatus status = memberStatusCacheService.getStatus(memberId);
                if (status == null) {
                    sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "账号已不存在");
                    return;
                }
                if (status == MemberStatus.BANNED) {
                    sendJsonError(request, response, HttpServletResponse.SC_FORBIDDEN, "账号已被封禁");
                    return;
                }
                authorities = authorities(role, claims);
            } else {
                String adminUsername = configService.value(IdentityConfigKey.ADMIN_USERNAME);
                boolean adminRole = !StringUtils.hasText(role) || IrcsPermissions.ROLE_ADMIN.equals(role);
                if (!adminRole || !subject.equals(adminUsername)) {
                    sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的身份标识");
                    return;
                }
                authorities = authorities(IrcsPermissions.ROLE_ADMIN, claims);
            }

            SecurityContextHolder.getContext().setAuthentication(authentication(subject, authorities));
            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException ex) {
            SecurityContextHolder.clearContext();
            sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "登录凭证已过期，请重新登录");
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的登录凭证");
        }
    }

    private boolean authenticateTrustedHeaders(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws IOException, ServletException {
        var principal = IrcsAuthHeaders.fromTrustedHeaders(request);
        if (principal.isEmpty()) {
            return false;
        }
        IrcsRequestPrincipal trustedPrincipal = principal.get();
        if (trustedPrincipal.isAnonymous()) {
            return false;
        }
        if (IrcsPermissions.ROLE_MEMBER.equals(trustedPrincipal.role())
                && !validateMemberStatus(trustedPrincipal.subject(), request, response)) {
            return true;
        }
        SecurityContextHolder.getContext().setAuthentication(authentication(
                trustedPrincipal.subject(),
                trustedPrincipal.role(),
                trustedPrincipal.permissions()));
        filterChain.doFilter(request, response);
        return true;
    }

    private boolean validateMemberStatus(
            String subject,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        UUID memberId;
        try {
            memberId = UUID.fromString(subject);
        } catch (RuntimeException ex) {
            sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的身份标识");
            return false;
        }
        MemberStatus status = memberStatusCacheService.getStatus(memberId);
        if (status == null) {
            sendJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "账号已不存在");
            return false;
        }
        if (status == MemberStatus.BANNED) {
            sendJsonError(request, response, HttpServletResponse.SC_FORBIDDEN, "账号已被封禁");
            return false;
        }
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private List<SimpleGrantedAuthority> authorities(String role, Claims claims) {
        return authorities(role, permissions(claims, role));
    }

    private UsernamePasswordAuthenticationToken authentication(
            String subject,
            String role,
            Collection<String> permissions) {
        return authentication(subject, authorities(role, permissions));
    }

    private UsernamePasswordAuthenticationToken authentication(
            String subject,
            List<SimpleGrantedAuthority> authorities) {
        return new UsernamePasswordAuthenticationToken(subject, null, authorities);
    }

    private List<SimpleGrantedAuthority> authorities(String role, Collection<String> permissions) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role));
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(IrcsPermissions.authority(permission)));
        }
        return List.copyOf(authorities);
    }

    private List<String> permissions(Claims claims, String role) {
        return claimValues(claims, IrcsAuthClaims.PERMISSIONS);
    }

    private void validateRequiredRbacClaims(Claims claims) {
        claimValues(claims, IrcsAuthClaims.PERMISSIONS);
        claimValues(claims, IrcsAuthClaims.SCOPES);
        claimValues(claims, IrcsAuthClaims.DATA_CATEGORIES);
        claimValues(claims, IrcsAuthClaims.DATA_GENRES);
        claimValues(claims, IrcsAuthClaims.DATA_TAGS);
        claimValues(claims, IrcsAuthClaims.CONTENT_VISIBILITY);
    }

    private List<String> claimValues(Claims claims, String claimName) {
        Object raw = claims.get(claimName);
        if (raw instanceof Collection<?> values) {
            List<String> result = values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!result.isEmpty()) {
                return result;
            }
        }
        if (raw instanceof String value && StringUtils.hasText(value)) {
            List<String> result = java.util.Arrays.stream(value.split("[,\\s]+"))
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!result.isEmpty()) {
                return result;
            }
        }
        throw new IllegalArgumentException("Token claim is missing: " + claimName);
    }

    private void sendJsonError(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String message) throws IOException {
        ApiErrorResponses.write(
                request,
                response,
                org.springframework.http.HttpStatusCode.valueOf(status),
                "auth.token.invalid",
                message,
                "auth");
    }
}
