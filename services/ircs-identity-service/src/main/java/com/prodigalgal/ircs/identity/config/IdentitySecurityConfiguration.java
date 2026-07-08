package com.prodigalgal.ircs.identity.config;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdentitySecurityConfiguration {

    @Bean
    SecureRandom identitySecureRandom() {
        return new SecureRandom();
    }
}
