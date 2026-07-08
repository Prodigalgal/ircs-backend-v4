package com.prodigalgal.ircs.identity.application;




import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.security.JwtTokenService;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminLoginRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminPasswordChangeRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AdminTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final IdentityConfigService configService;
    private final PasswordEncoder passwordEncoder;
    private final PoWService poWService;
    private final JwtTokenService jwtTokenService;

    public AdminTokenResponse login(AdminLoginRequest request) {
        poWService.verifyIfPresent(request.powVerification());

        String adminUsername = configService.value(IdentityConfigKey.ADMIN_USERNAME);
        String passwordHash = configService.value(IdentityConfigKey.ADMIN_PASSWORD);
        if (!StringUtils.hasText(passwordHash)
                || !adminUsername.equals(request.username())
                || !passwordEncoder.matches(request.password(), passwordHash)) {
            throw ApiException.unauthorized("用户名或密码错误", "admin", "auth.failed");
        }
        return new AdminTokenResponse(jwtTokenService.generateAdminToken(adminUsername));
    }

    @Transactional
    public void changePassword(AdminPasswordChangeRequest request) {
        if (configService.hasInjectedValue(IdentityConfigKey.ADMIN_PASSWORD)) {
            throw ApiException.badRequest(
                    "管理员密码已由环境注入覆盖，请修改 K8S Secret 或启动环境变量。",
                    "admin",
                    "password.injected");
        }
        String passwordHash = configService.value(IdentityConfigKey.ADMIN_PASSWORD);
        if (!StringUtils.hasText(passwordHash) || !passwordEncoder.matches(request.oldPassword(), passwordHash)) {
            throw ApiException.forbidden("当前旧密码输入错误", "admin", "password.invalid");
        }

        configService.updateValue(IdentityConfigKey.ADMIN_PASSWORD, passwordEncoder.encode(request.newPassword()));
        configService.updateValue(IdentityConfigKey.JWT_IAT_FLOOR, String.valueOf(System.currentTimeMillis() / 1000));
    }
}
