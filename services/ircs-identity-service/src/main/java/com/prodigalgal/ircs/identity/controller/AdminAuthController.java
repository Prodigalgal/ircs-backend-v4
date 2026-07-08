package com.prodigalgal.ircs.identity.controller;

import com.prodigalgal.ircs.identity.application.AdminAuthService;
import com.prodigalgal.ircs.identity.application.PoWService;

import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminPasswordChangeRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminTokenResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWChallenge;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService authService;
    private final PoWService poWService;

    @GetMapping("/pow")
    public ResponseEntity<PoWChallenge> getChallenge(
            @RequestParam(name = "scope", defaultValue = "admin.login") String scope) {
        return ResponseEntity.ok(poWService.generateChallenge(scope));
    }

    @PostMapping("/login")
    public ResponseEntity<AdminTokenResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody AdminPasswordChangeRequest request) {
        authService.changePassword(request);
        return ResponseEntity.ok(Map.of("message", "密码修改成功，所有设备已强制下线"));
    }
}
