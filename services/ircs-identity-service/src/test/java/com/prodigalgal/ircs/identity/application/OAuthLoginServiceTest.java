package com.prodigalgal.ircs.identity.application;












import com.prodigalgal.ircs.identity.repository.MemberOAuthAccountRepository;
import com.prodigalgal.ircs.identity.infrastructure.OAuthProviderClient;
import com.prodigalgal.ircs.identity.domain.OAuthUserProfile;
import com.prodigalgal.ircs.identity.domain.OAuthAccessToken;
import com.prodigalgal.ircs.identity.domain.OAuthProviderRegistry;
import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.security.JwtTokenService;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberOAuthAccountRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class OAuthLoginServiceTest {

    private final OAuthProviderConfigResolver configResolver = mock(OAuthProviderConfigResolver.class);
    private final OAuthProviderClient providerClient = mock(OAuthProviderClient.class);
    private final MemberOAuthAccountRepository oAuthAccountRepository = mock(MemberOAuthAccountRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final IdentityConfigService configService = mock(IdentityConfigService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final MemberStatusCacheService statusCacheService = mock(MemberStatusCacheService.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final MemberResponseMapper mapper = new MemberResponseMapper();

    private OAuthLoginService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(configResolver.frontendBaseUrl()).thenReturn("https://huawai.mnnu.eu.org");
        service = new OAuthLoginService(
                configResolver,
                providerClient,
                oAuthAccountRepository,
                memberRepository,
                passwordEncoder,
                configService,
                redisTemplate,
                statusCacheService,
                jwtTokenService,
                mapper);
    }

    @Test
    void startRedirectStoresStateAndUsesPkceForX() {
        when(configResolver.findUsable("x")).thenReturn(Optional.of(config("x")));

        URI redirect = service.authorizationRedirect("x");

        assertTrue(redirect.toString().startsWith("https://twitter.com/i/oauth2/authorize"));
        assertTrue(redirect.toString().contains("code_challenge_method=S256"));
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), any());
        assertTrue(keyCaptor.getValue().startsWith("auth:oauth_state:"));
        assertTrue(valueCaptor.getValue().startsWith("x|"));
    }

    @Test
    void callbackAutoRegistersVerifiedEmailAndLinksAccount() {
        OAuthProviderConfig config = config("google");
        OAuthAccessToken providerToken = new OAuthAccessToken("provider-token", "Bearer", null);
        OAuthUserProfile profile = new OAuthUserProfile(
                "google",
                "google-subject",
                "CODEX@example.com",
                true,
                "Codex User",
                "https://avatar.example.com/codex.png");
        when(configResolver.findUsable("google")).thenReturn(Optional.of(config));
        when(valueOperations.get("auth:oauth_state:state-1")).thenReturn("google|");
        when(providerClient.exchangeCode(config, "code-1", "")).thenReturn(providerToken);
        when(providerClient.fetchUserProfile(config, providerToken)).thenReturn(profile);
        when(oAuthAccountRepository.findByProviderAndSubject("google", "google-subject")).thenReturn(Optional.empty());
        when(memberRepository.findByEmail("codex@example.com")).thenReturn(Optional.empty());
        when(configService.bool("member.oauth.email-ownership-verification.enabled", true)).thenReturn(true);
        when(configService.bool("member.oauth.auto-register.enabled", true)).thenReturn(true);
        when(memberRepository.insert(any(MemberRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtTokenService.generateMemberToken(any(MemberRecord.class))).thenReturn("member.jwt.token");

        URI redirect = service.callbackRedirect("google", "code-1", "state-1", null);

        assertTrue(redirect.toString().equals("https://huawai.mnnu.eu.org/oauth/callback#token=member.jwt.token"));
        verify(oAuthAccountRepository).insert(any(MemberOAuthAccountRecord.class));
    }

    @Test
    void callbackDoesNotBindExistingEmailByDefault() {
        OAuthProviderConfig config = config("google");
        OAuthAccessToken providerToken = new OAuthAccessToken("provider-token", "Bearer", null);
        OAuthUserProfile profile = new OAuthUserProfile(
                "google",
                "google-subject",
                "codex@example.com",
                true,
                "Codex User",
                null);
        MemberRecord existing = MemberRecord.newMember(
                "codex@example.com",
                passwordEncoder.encode("Pass123"),
                "Codex",
                MemberStatus.ACTIVE);
        when(configResolver.findUsable("google")).thenReturn(Optional.of(config));
        when(valueOperations.get("auth:oauth_state:state-2")).thenReturn("google|");
        when(providerClient.exchangeCode(config, "code-2", "")).thenReturn(providerToken);
        when(providerClient.fetchUserProfile(config, providerToken)).thenReturn(profile);
        when(oAuthAccountRepository.findByProviderAndSubject("google", "google-subject")).thenReturn(Optional.empty());
        when(memberRepository.findByEmail("codex@example.com")).thenReturn(Optional.of(existing));
        when(configService.bool("member.oauth.email-ownership-verification.enabled", true)).thenReturn(true);
        when(configService.bool("member.oauth.bind-existing-email.enabled", false)).thenReturn(false);

        URI redirect = service.callbackRedirect("google", "code-2", "state-2", null);

        assertTrue(redirect.toString().contains("error=oauth.email.exists"));
        verify(oAuthAccountRepository, never()).insert(any(MemberOAuthAccountRecord.class));
    }

    @Test
    void callbackRejectsUnverifiedEmailWhenOwnershipVerificationIsRequired() {
        OAuthProviderConfig config = config("google");
        OAuthAccessToken providerToken = new OAuthAccessToken("provider-token", "Bearer", null);
        OAuthUserProfile profile = new OAuthUserProfile(
                "google",
                "google-subject",
                "codex@example.com",
                false,
                "Codex User",
                null);
        when(configResolver.findUsable("google")).thenReturn(Optional.of(config));
        when(valueOperations.get("auth:oauth_state:state-3")).thenReturn("google|");
        when(providerClient.exchangeCode(config, "code-3", "")).thenReturn(providerToken);
        when(providerClient.fetchUserProfile(config, providerToken)).thenReturn(profile);
        when(oAuthAccountRepository.findByProviderAndSubject("google", "google-subject")).thenReturn(Optional.empty());
        when(configService.bool("member.oauth.email-ownership-verification.enabled", true)).thenReturn(true);

        URI redirect = service.callbackRedirect("google", "code-3", "state-3", null);

        assertTrue(redirect.toString().contains("error=oauth.email.unverified"));
        verify(memberRepository, never()).findByEmail(eq("codex@example.com"));
    }

    private static OAuthProviderConfig config(String code) {
        OAuthProviderRegistry.Definition definition = OAuthProviderRegistry.find(code).orElseThrow();
        return new OAuthProviderConfig(
                definition,
                "client-id",
                "client-secret",
                definition.defaultScope(),
                URI.create("https://huawai.mnnu.eu.org" + definition.defaultRedirectPath()),
                "https://huawai.mnnu.eu.org");
    }
}
