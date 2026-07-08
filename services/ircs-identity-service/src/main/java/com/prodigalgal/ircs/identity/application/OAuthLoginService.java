package com.prodigalgal.ircs.identity.application;














import com.prodigalgal.ircs.identity.repository.MemberOAuthAccountRepository;
import com.prodigalgal.ircs.identity.infrastructure.OAuthProviderClient;
import com.prodigalgal.ircs.identity.domain.OAuthUserProfile;
import com.prodigalgal.ircs.identity.domain.OAuthAccessToken;
import com.prodigalgal.ircs.identity.domain.OAuthProviderRegistry;
import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.security.JwtTokenService;
import com.prodigalgal.ircs.identity.IdentityRedisKeys;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberOAuthAccountRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberTokenResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthProviderConfigResolver configResolver;
    private final OAuthProviderClient providerClient;
    private final MemberOAuthAccountRepository oAuthAccountRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityConfigService configService;
    private final StringRedisTemplate redisTemplate;
    private final MemberStatusCacheService statusCacheService;
    private final JwtTokenService jwtTokenService;
    private final MemberResponseMapper mapper;

    public URI authorizationRedirect(String providerCode) {
        OAuthProviderConfig config = configResolver.findUsable(providerCode)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "OAuth provider is not enabled or configured",
                        "oauth",
                        "oauth.provider.unavailable"));
        String state = randomUrlToken(32);
        String codeVerifier = config.definition().pkceRequired() ? randomUrlToken(48) : "";
        redisTemplate.opsForValue().set(
                IdentityRedisKeys.oauthState(state),
                new OAuthStatePayload(config.code(), codeVerifier).encode(),
                STATE_TTL);

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.definition().authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", config.clientId())
                .queryParam("redirect_uri", config.redirectUri().toString())
                .queryParam("scope", config.scope())
                .queryParam("state", state);
        if (StringUtils.hasText(codeVerifier)) {
            builder.queryParam("code_challenge", sha256Base64Url(codeVerifier))
                    .queryParam("code_challenge_method", "S256");
        }
        if (config.definition().kind() == OAuthProviderRegistry.Kind.GOOGLE) {
            builder.queryParam("prompt", "select_account");
        }
        if (config.definition().kind() == OAuthProviderRegistry.Kind.WECHAT) {
            builder.queryParam("appid", config.clientId());
        }
        return builder.build().encode().toUri();
    }

    @Transactional
    public URI callbackRedirect(String providerCode, String code, String state, String providerError) {
        if (StringUtils.hasText(providerError)) {
            return failureRedirect("oauth.denied", "第三方授权已取消或失败");
        }
        try {
            OAuthProviderConfig config = configResolver.findUsable(providerCode)
                    .orElseThrow(() -> ApiException.notFound(
                            "OAuth provider is not enabled or configured",
                            "oauth",
                            "oauth.provider.unavailable"));
            OAuthStatePayload statePayload = consumeState(state);
            if (!config.code().equals(statePayload.provider())) {
                throw ApiException.badRequest("OAuth state mismatch", "oauth", "oauth.state.invalid");
            }
            if (!StringUtils.hasText(code)) {
                throw ApiException.badRequest("OAuth authorization code is missing", "oauth", "oauth.code.missing");
            }

            OAuthAccessToken token = providerClient.exchangeCode(config, code, statePayload.codeVerifier());
            OAuthUserProfile profile = providerClient.fetchUserProfile(config, token);
            MemberRecord member = resolveMember(profile, token);
            return successRedirect(mapper.toToken(member, jwtTokenService.generateMemberToken(member)));
        } catch (ApiException ex) {
            return failureRedirect(ex.errorKey(), ex.getMessage());
        }
    }

    private OAuthStatePayload consumeState(String state) {
        if (!StringUtils.hasText(state)) {
            throw ApiException.badRequest("OAuth state is missing", "oauth", "oauth.state.missing");
        }
        String key = IdentityRedisKeys.oauthState(state);
        String raw = redisTemplate.opsForValue().get(key);
        redisTemplate.delete(key);
        if (!StringUtils.hasText(raw)) {
            throw ApiException.badRequest("OAuth state expired or invalid", "oauth", "oauth.state.invalid");
        }
        return OAuthStatePayload.decode(raw);
    }

    private MemberRecord resolveMember(OAuthUserProfile profile, OAuthAccessToken token) {
        String provider = OAuthProviderRegistry.normalize(profile.provider());
        String subject = required(profile.subject(), "oauth.subject.missing");
        String accessTokenHash = sha256(token.accessToken());
        return oAuthAccountRepository.findByProviderAndSubject(provider, subject)
                .map(account -> loginLinkedMember(account, profile, accessTokenHash))
                .orElseGet(() -> linkOrRegisterMember(profile, accessTokenHash));
    }

    private MemberRecord loginLinkedMember(
            MemberOAuthAccountRecord account,
            OAuthUserProfile profile,
            String accessTokenHash) {
        MemberRecord member = memberRepository.findById(account.memberId())
                .orElseThrow(() -> ApiException.notFound("绑定的会员不存在", "oauth", "oauth.member.not.found"));
        MemberRecord usable = requireUsable(member, false);
        oAuthAccountRepository.update(account.withProfile(profile, accessTokenHash));
        statusCacheService.updateStatus(usable.id(), usable.status());
        return usable;
    }

    private MemberRecord linkOrRegisterMember(OAuthUserProfile profile, String accessTokenHash) {
        String email = normalizeEmail(profile.email());
        if (!StringUtils.hasText(email)) {
            throw ApiException.badRequest("第三方账号未返回邮箱，无法首次登录", "oauth", "oauth.email.missing");
        }
        if (emailOwnershipVerificationRequired() && !profile.emailVerified()) {
            throw ApiException.badRequest("第三方账号邮箱未验证，无法确认邮箱归属", "oauth", "oauth.email.unverified");
        }

        return memberRepository.findByEmail(email)
                .map(member -> linkExistingMember(member, profile, accessTokenHash))
                .orElseGet(() -> registerMember(profile, email, accessTokenHash));
    }

    private MemberRecord linkExistingMember(
            MemberRecord member,
            OAuthUserProfile profile,
            String accessTokenHash) {
        if (!configService.bool("member.oauth.bind-existing-email.enabled", false)) {
            throw ApiException.badRequest(
                    "该邮箱已存在，请先使用邮箱登录并验证归属后再绑定第三方账号",
                    "oauth",
                    "oauth.email.exists");
        }
        MemberRecord usable = requireUsable(member, profile.emailVerified());
        oAuthAccountRepository.insert(MemberOAuthAccountRecord.newAccount(usable.id(), profile, accessTokenHash));
        statusCacheService.updateStatus(usable.id(), usable.status());
        return usable;
    }

    private MemberRecord registerMember(OAuthUserProfile profile, String email, String accessTokenHash) {
        if (!configService.bool("member.oauth.auto-register.enabled", true)) {
            throw ApiException.badRequest("当前未开放第三方登录自动注册", "oauth", "oauth.auto-register.disabled");
        }
        MemberRecord member = MemberRecord.newMember(
                email,
                passwordEncoder.encode(randomUrlToken(32)),
                sanitizeNickname(profile.nickname(), email),
                MemberStatus.ACTIVE);
        if (StringUtils.hasText(profile.avatarUrl())) {
            member = member.withAvatarUrl(profile.avatarUrl().trim());
        }
        MemberRecord inserted = memberRepository.insert(member);
        oAuthAccountRepository.insert(MemberOAuthAccountRecord.newAccount(inserted.id(), profile, accessTokenHash));
        statusCacheService.updateStatus(inserted.id(), inserted.status());
        return inserted;
    }

    private MemberRecord requireUsable(MemberRecord member, boolean verifiedEmail) {
        if (member.status() == MemberStatus.BANNED) {
            throw ApiException.forbidden("账号已被封禁", "oauth", "oauth.member.banned");
        }
        if (member.status() == MemberStatus.PENDING_VERIFICATION) {
            if (verifiedEmail) {
                MemberRecord activated = member.withStatus(MemberStatus.ACTIVE);
                memberRepository.update(activated);
                return activated;
            }
            throw ApiException.badRequest("请先完成邮箱验证后再使用第三方登录", "oauth", "oauth.member.unverified");
        }
        return member;
    }

    private boolean emailOwnershipVerificationRequired() {
        return configService.bool("member.oauth.email-ownership-verification.enabled", true);
    }

    private URI successRedirect(MemberTokenResponse response) {
        return URI.create(configResolver.frontendBaseUrl() + "/oauth/callback#token=" + urlEncode(response.token()));
    }

    private URI failureRedirect(String error, String message) {
        return UriComponentsBuilder.fromUriString(configResolver.frontendBaseUrl())
                .path("/oauth/callback")
                .queryParam("error", error)
                .queryParam("message", message)
                .build()
                .encode()
                .toUri();
    }

    private static String sanitizeNickname(String nickname, String email) {
        String raw = StringUtils.hasText(nickname) ? nickname : email.split("@", 2)[0];
        String sanitized = raw.replaceAll("<[^>]*>", "").trim();
        if (sanitized.length() < 2) {
            sanitized = "会员" + randomUrlToken(4);
        }
        return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
    }

    private static String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static String required(String value, String errorKey) {
        if (!StringUtils.hasText(value)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OAuth provider response is missing required data", "oauth", errorKey);
        }
        return value;
    }

    private static String randomUrlToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String sha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record OAuthStatePayload(String provider, String codeVerifier) {

        String encode() {
            return provider + "|" + (codeVerifier == null ? "" : codeVerifier);
        }

        static OAuthStatePayload decode(String raw) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length == 0 || !StringUtils.hasText(parts[0])) {
                throw ApiException.badRequest("OAuth state expired or invalid", "oauth", "oauth.state.invalid");
            }
            return new OAuthStatePayload(parts[0], parts.length > 1 ? parts[1] : "");
        }
    }
}
