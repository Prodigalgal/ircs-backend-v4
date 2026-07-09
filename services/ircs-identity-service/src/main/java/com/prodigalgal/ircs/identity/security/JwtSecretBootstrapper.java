package com.prodigalgal.ircs.identity.security;



import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.application.IdentityConfigService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
class JwtSecretBootstrapper implements ApplicationRunner {

    private static final int SECRET_BYTES = 48;

    private final IdentityConfigService configService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void run(ApplicationArguments args) {
        if (StringUtils.hasText(configService.value(IdentityConfigKey.JWT_SECRET))) {
            log.info("JWT secret already configured; runtime bootstrap skipped");
            return;
        }
        String secret = generateSecret();
        configService.installRuntimeValues(Map.of(IdentityConfigKey.JWT_SECRET, secret));
        configService.updateValue(IdentityConfigKey.JWT_SECRET, secret);
        log.warn("Generated a new runtime JWT secret for this identity-service startup; existing JWTs are invalidated");
    }

    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
