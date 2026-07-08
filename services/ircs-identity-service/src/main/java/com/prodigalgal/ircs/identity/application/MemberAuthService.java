package com.prodigalgal.ircs.identity.application;









import com.prodigalgal.ircs.identity.messaging.MailPublisher;
import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.security.JwtTokenService;
import com.prodigalgal.ircs.identity.IdentityRedisKeys;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.contracts.notification.MailMessageDTO;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AccountActivateRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ForgotPasswordRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberRegisterRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberTokenResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ResetPasswordRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberAuthService {

    private static final int RESET_REQUEST_LIMIT = 5;
    private static final Duration RESET_REQUEST_WINDOW = Duration.ofMinutes(15);
    private static final int RESET_TOKEN_ATTEMPT_LIMIT = 5;
    private static final Duration RESET_TOKEN_ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityConfigService configService;
    private final PoWService poWService;
    private final StringRedisTemplate redisTemplate;
    private final MailPublisher mailPublisher;
    private final MemberStatusCacheService statusCacheService;
    private final JwtTokenService jwtTokenService;
    private final MemberResponseMapper mapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public boolean register(MemberRegisterRequest request) {
        poWService.verifyIfPresent(request.powVerification());
        String email = normalizeEmail(request.email());
        if (memberRepository.existsByEmail(email)) {
            throw ApiException.badRequest("邮箱已被注册", "member", "email.exists");
        }

        boolean verifyEnabled = configService.bool(IdentityConfigKey.MEMBER_REGISTER_EMAIL_VERIFY_ENABLED);
        MemberStatus status = verifyEnabled ? MemberStatus.PENDING_VERIFICATION : MemberStatus.ACTIVE;
        MemberRecord member = MemberRecord.newMember(
                email,
                passwordEncoder.encode(request.password()),
                sanitizeNickname(request.nickname()),
                status);
        memberRepository.insert(member);
        statusCacheService.updateStatus(member.id(), status);
        if (verifyEnabled) {
            sendActivationCode(email);
        }
        return verifyEnabled;
    }

    @Transactional
    public MemberTokenResponse activate(AccountActivateRequest request) {
        String email = normalizeEmail(request.email());
        String key = IdentityRedisKeys.authActivate(email);
        String savedCode = redisTemplate.opsForValue().get(key);
        if (savedCode == null) {
            throw ApiException.badRequest("激活码已过期或不存在，请点击重新发送", "member", "code.expired");
        }
        if (!savedCode.equals(request.code())) {
            throw ApiException.badRequest("激活码错误", "member", "code.invalid");
        }

        MemberRecord member = memberRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.notFound("用户不存在", "member", "not.found"));
        if (member.status() == MemberStatus.BANNED) {
            throw ApiException.forbidden("账号已被封禁", "member", "auth.banned");
        }
        MemberRecord activated = member.status() == MemberStatus.ACTIVE ? member : member.withStatus(MemberStatus.ACTIVE);
        if (member.status() != MemberStatus.ACTIVE) {
            memberRepository.update(activated);
        }
        statusCacheService.updateStatus(activated.id(), activated.status());
        redisTemplate.delete(key);
        return tokenResponse(activated);
    }

    @Transactional
    public MemberTokenResponse login(MemberLoginRequest request) {
        poWService.verifyIfPresent(request.powVerification());
        MemberRecord member = memberRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> ApiException.badRequest("账号或密码错误", "member", "auth.failed"));
        if (!passwordEncoder.matches(request.password(), member.passwordHash())) {
            throw ApiException.badRequest("账号或密码错误", "member", "auth.failed");
        }
        if (member.status() == MemberStatus.BANNED) {
            throw ApiException.forbidden("账号已被封禁", "member", "auth.banned");
        }

        MemberRecord effective = member;
        boolean verifyEnabled = configService.bool(IdentityConfigKey.MEMBER_REGISTER_EMAIL_VERIFY_ENABLED);
        if (member.status() == MemberStatus.PENDING_VERIFICATION) {
            if (verifyEnabled) {
                throw ApiException.badRequest("请先通过邮件激活您的账号", "member", "auth.unverified");
            }
            effective = member.withStatus(MemberStatus.ACTIVE);
            memberRepository.update(effective);
        }
        statusCacheService.updateStatus(effective.id(), effective.status());
        return tokenResponse(effective);
    }

    public void resendCode(String emailValue) {
        String email = normalizeEmail(emailValue);
        MemberRecord member = memberRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.notFound("用户不存在", "member", "not.found"));
        if (member.status() != MemberStatus.PENDING_VERIFICATION) {
            throw ApiException.badRequest("该账号无需激活", "member", "status.invalid");
        }

        String key = IdentityRedisKeys.authActivate(email);
        Long expire = redisTemplate.getExpire(key);
        long validitySeconds = configService.intValue(IdentityConfigKey.MEMBER_CODE_VALIDITY_SECONDS);
        long rateLimitSeconds = configService.intValue(IdentityConfigKey.MEMBER_RATE_LIMIT_SECONDS);
        long threshold = validitySeconds - rateLimitSeconds;
        if (expire != null && expire > threshold) {
            throw ApiException.badRequest("发送太频繁，请等待 " + (expire - threshold) + " 秒", "member", "rate.limit");
        }
        sendActivationCode(email);
    }

    @Transactional(readOnly = true)
    public void requestPasswordReset(ForgotPasswordRequest request) {
        if (!configService.bool(IdentityConfigKey.MAIL_ENABLED)) {
            throw ApiException.badRequest("当前系统未开启邮件服务，无法重置密码。请联系管理员。", "system", "mail.disabled");
        }
        poWService.verifyIfPresent(request.powVerification());
        String email = normalizeEmail(request.email());
        enforceCounterLimit(
                IdentityRedisKeys.authResetRequest(email),
                RESET_REQUEST_LIMIT,
                RESET_REQUEST_WINDOW,
                "请求太频繁，请稍后再试",
                "member",
                "reset.rate.limit");
        MemberRecord member = memberRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.badRequest("用户不存在或信息不匹配", "member", "auth.failed"));
        if (!member.nickname().equals(request.nickname())) {
            throw ApiException.badRequest("用户不存在或信息不匹配", "member", "auth.failed");
        }

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(IdentityRedisKeys.authResetToken(token), member.id().toString(), Duration.ofMinutes(15));
        String frontendUrl = requireFrontendUrl().replaceAll("/+$", "");
        mailPublisher.publish(MailMessageDTO.builder()
                .to(member.email())
                .subject("【画外 Huawai】重置您的密码")
                .templateCode("mail/reset-password")
                .variables(Map.of("nickname", member.nickname(), "link", frontendUrl + "/reset-password?token=" + token))
                .html(true)
                .build());
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String redisKey = IdentityRedisKeys.authResetToken(request.token());
        String memberIdValue = redisTemplate.opsForValue().get(redisKey);
        if (memberIdValue == null) {
            enforceCounterLimit(
                    IdentityRedisKeys.authResetAttempt(sha256(request.token())),
                    RESET_TOKEN_ATTEMPT_LIMIT,
                    RESET_TOKEN_ATTEMPT_WINDOW,
                    "重置链接尝试过多，请稍后再试",
                    "member",
                    "reset.token.rate.limit");
            throw ApiException.badRequest("重置链接无效或已过期", "member", "token.invalid");
        }
        UUID memberId = UUID.fromString(memberIdValue);
        MemberRecord member = memberRepository.findById(memberId)
                .orElseThrow(() -> ApiException.notFound("用户不存在", "member", "not.found"));
        memberRepository.update(member.withPasswordHash(passwordEncoder.encode(request.newPassword())));
        redisTemplate.delete(redisKey);
        redisTemplate.delete(IdentityRedisKeys.authResetAttempt(sha256(request.token())));
    }

    private MemberTokenResponse tokenResponse(MemberRecord member) {
        return mapper.toToken(member, jwtTokenService.generateMemberToken(member));
    }

    private void sendActivationCode(String email) {
        if (!configService.bool(IdentityConfigKey.MAIL_ENABLED)) {
            return;
        }
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        long validitySeconds = configService.intValue(IdentityConfigKey.MEMBER_CODE_VALIDITY_SECONDS);
        redisTemplate.opsForValue().set(IdentityRedisKeys.authActivate(email), code, Duration.ofSeconds(validitySeconds));
        mailPublisher.publish(MailMessageDTO.builder()
                .to(email)
                .subject("【画外 Huawai】注册激活验证码")
                .templateCode("mail/activation")
                .variables(Map.of("code", code))
                .html(true)
                .build());
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "邮箱不能为空", "member", "email.blank");
        }
        return email.trim().toLowerCase();
    }

    private String requireFrontendUrl() {
        String frontendUrl = configService.value(IdentityConfigKey.FRONTEND_URL);
        if (frontendUrl == null || frontendUrl.isBlank()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Frontend URL is not configured",
                    "system",
                    "frontend.url.missing");
        }
        return frontendUrl.trim();
    }

    private void enforceCounterLimit(
            String key,
            int limit,
            Duration window,
            String message,
            String entity,
            String errorKey) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count != null && count > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, message, entity, errorKey);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String sanitizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String sanitized = nickname.replaceAll("<[^>]*>", "").trim();
        if (sanitized.length() < 2) {
            throw ApiException.badRequest("昵称包含非法字符", "member", "nickname.invalid");
        }
        return sanitized;
    }
}
