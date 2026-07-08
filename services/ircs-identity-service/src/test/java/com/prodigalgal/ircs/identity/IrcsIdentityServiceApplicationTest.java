package com.prodigalgal.ircs.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

class IrcsIdentityServiceApplicationTest {

    @Test
    void excludesDefaultUserDetailsAutoConfiguration() {
        var annotation = IrcsIdentityServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(annotation.exclude()).contains(UserDetailsServiceAutoConfiguration.class);
    }
}
