package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.security.IrcsAuthException;
import com.prodigalgal.ircs.common.security.IrcsJwtAuthDecoder;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MemberTokenService {

    private static final String JWT_SECRET_DB_KEY = "security.jwt.secret";
    private static final String JWT_SECRET_PROPERTY = "app.identity.jwt.secret";
    private static final String JWT_SECRET_FALLBACK = "";
    private static final String JWT_IAT_FLOOR_DB_KEY = "security.jwt.iat-floor";
    private static final String JWT_IAT_FLOOR_PROPERTY = "app.identity.jwt.iat-floor";

    private final SystemConfigRepository systemConfigRepository;
    private final JdbcInteractionRepository interactionRepository;
    private final Environment environment;
    private final IrcsJwtAuthDecoder authDecoder = new IrcsJwtAuthDecoder();

    public UUID requireMemberId(String authorizationHeader) {
        String token = resolveToken(authorizationHeader)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "缺少登录凭证"));

        IrcsRequestPrincipal principal = decode(token);
        if (!IrcsPermissions.ROLE_MEMBER.equals(principal.role())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "无效的身份标识");
        }

        UUID memberId = parseMemberId(principal.subject());
        String status = interactionRepository.findMemberStatus(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号已不存在"));
        if ("BANNED".equals(status)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号已被封禁");
        }
        return memberId;
    }

    private Optional<String> resolveToken(String authorizationHeader) {
        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7).trim();
            if (StringUtils.hasText(token)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    private IrcsRequestPrincipal decode(String token) {
        try {
            return authDecoder.decode(token, jwtSecret(), iatFloorSeconds());
        } catch (IrcsAuthException ex) {
            throw switch (ex.reason()) {
                case EXPIRED -> new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证已过期，请重新登录");
                case STALE -> new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证已失效，请重新登录");
                case FORBIDDEN -> new ApiException(HttpStatus.FORBIDDEN, "权限不足");
                case MISSING, INVALID -> new ApiException(HttpStatus.UNAUTHORIZED, "无效的登录凭证");
            };
        }
    }

    private UUID parseMemberId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "无效的身份标识");
        }
    }

    private String jwtSecret() {
        return configValue(JWT_SECRET_DB_KEY, JWT_SECRET_PROPERTY, JWT_SECRET_FALLBACK);
    }

    private long iatFloorSeconds() {
        String raw = configValue(JWT_IAT_FLOOR_DB_KEY, JWT_IAT_FLOOR_PROPERTY, "0");
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String configValue(String dbKey, String propertyKey, String fallback) {
        Optional<String> injectedValue = RuntimeInjectedConfig.find(environment, propertyKey, dbKey);
        if (injectedValue.isPresent()) {
            return injectedValue.get();
        }
        return systemConfigRepository.findValue(dbKey)
                .filter(StringUtils::hasText)
                .orElse(fallback);
    }
}
