package com.prodigalgal.ircs.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IrcsJwtRuntimeConfigResolverTest {

    @Test
    void envOverridesFallback() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_IDENTITY_JWT_SECRET", "env-secret")
                .withProperty("APP_IDENTITY_JWT_IAT_FLOOR", "123");

        IrcsJwtRuntimeConfigResolver resolver = new IrcsJwtRuntimeConfigResolver(environment, null, null, null);

        assertThat(resolver.jwtSecret()).isEqualTo("env-secret");
        assertThat(resolver.iatFloorSeconds()).isEqualTo(123);
    }
}
