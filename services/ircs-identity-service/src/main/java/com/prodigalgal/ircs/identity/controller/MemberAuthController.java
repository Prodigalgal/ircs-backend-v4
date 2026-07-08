package com.prodigalgal.ircs.identity.controller;

import com.prodigalgal.ircs.identity.application.MemberAuthService;
import com.prodigalgal.ircs.identity.application.OAuthLoginService;
import com.prodigalgal.ircs.identity.dto.OAuthProviderSettings;
import com.prodigalgal.ircs.identity.application.OAuthProviderSettingsService;
import com.prodigalgal.ircs.identity.application.PoWService;

import com.prodigalgal.ircs.identity.dto.IdentityDtos.AccountActivateRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ForgotPasswordRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberRegisterRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberTokenResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWChallenge;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ResendCodeRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ResetPasswordRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portal/auth")
@RequiredArgsConstructor
public class MemberAuthController {

    private final MemberAuthService authService;
    private final PoWService poWService;
    private final OAuthProviderSettingsService oAuthProviderSettingsService;
    private final OAuthLoginService oAuthLoginService;

    @GetMapping("/pow")
    public ResponseEntity<PoWChallenge> getChallenge(
            @RequestParam(name = "scope", defaultValue = "portal.login") String scope) {
        return ResponseEntity.ok(poWService.generateChallenge(scope));
    }

    @GetMapping("/oauth/providers")
    public ResponseEntity<OAuthProviderSettings> oauthProviders() {
        return ResponseEntity.ok(oAuthProviderSettingsService.publicSettings());
    }

    @GetMapping("/oauth/{provider}/start")
    public ResponseEntity<Void> startOAuth(@PathVariable(name = "provider") String provider) {
        return redirect(oAuthLoginService.authorizationRedirect(provider));
    }

    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> completeOAuth(
            @PathVariable(name = "provider") String provider,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error) {
        return redirect(oAuthLoginService.callbackRedirect(provider, code, state, error));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody MemberRegisterRequest request) {
        boolean verifyRequired = authService.register(request);
        String message = verifyRequired ? "注册成功，请查收邮件激活码" : "注册成功，请直接登录";
        return ResponseEntity.ok(Map.of("message", message, "verifyRequired", verifyRequired));
    }

    @PostMapping("/activate")
    public ResponseEntity<MemberTokenResponse> activate(@Valid @RequestBody AccountActivateRequest request) {
        return ResponseEntity.ok(authService.activate(request));
    }

    @PostMapping("/login")
    public ResponseEntity<MemberTokenResponse> login(@Valid @RequestBody MemberLoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/resend-code")
    public ResponseEntity<Map<String, String>> resendCode(@Valid @RequestBody ResendCodeRequest request) {
        authService.resendCode(request.email());
        return ResponseEntity.ok(Map.of("message", "验证码已发送"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok(Map.of("message", "重置链接已发送至您的邮箱"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "密码重置成功，请重新登录"));
    }

    private ResponseEntity<Void> redirect(URI uri) {
        return ResponseEntity.status(HttpStatus.FOUND).location(uri).build();
    }
}
