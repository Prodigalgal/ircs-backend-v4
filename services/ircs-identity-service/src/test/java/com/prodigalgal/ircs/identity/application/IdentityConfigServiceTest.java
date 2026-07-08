package com.prodigalgal.ircs.identity.application;




import com.prodigalgal.ircs.identity.messaging.SystemConfigChangePublisher;
import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.infrastructure.SystemConfigRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class IdentityConfigServiceTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final SystemConfigChangePublisher changePublisher = org.mockito.Mockito.mock(SystemConfigChangePublisher.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final IdentityConfigService service =
            new IdentityConfigService(repository, environment, passwordEncoder, changePublisher);

    @Test
    void injectedPropertyOverridesStoredConfig() {
        environment.setProperty("app.identity.jwt.secret", "env-secret");

        assertEquals("env-secret", service.value(IdentityConfigKey.JWT_SECRET));
        verifyNoInteractions(repository);
    }

    @Test
    void runtimeValueOverridesInjectedAndStoredConfig() {
        environment.setProperty("APP_IDENTITY_JWT_SECRET", "env-secret");
        service.installRuntimeValues(Map.of(IdentityConfigKey.JWT_SECRET, "runtime-secret"));

        assertEquals("runtime-secret", service.value(IdentityConfigKey.JWT_SECRET));
        verifyNoInteractions(repository);
    }

    @Test
    void k8sEnvironmentNameOverridesStoredConfig() {
        environment.setProperty("APP_IDENTITY_JWT_SECRET", "env-secret");

        assertEquals("env-secret", service.value(IdentityConfigKey.JWT_SECRET));
        verifyNoInteractions(repository);
    }

    @Test
    void configTreeSecretOverridesStoredConfig() {
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Config tree '/etc/secrets/ircs/'",
                java.util.Map.of("app.identity.jwt.secret", "env-secret")));

        assertEquals("env-secret", service.value(IdentityConfigKey.JWT_SECRET));
        verifyNoInteractions(repository);
    }

    @Test
    void applicationConfigDefaultDoesNotOverrideStoredConfig() {
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
                java.util.Map.of("app.identity.jwt.secret", "yaml-default-secret")));
        when(repository.findValue("security.jwt.secret")).thenReturn(Optional.of("db-secret"));

        assertEquals("db-secret", service.value(IdentityConfigKey.JWT_SECRET));
    }

    @Test
    void storedConfigOverridesFallbackWhenNoInjectedValueExists() {
        when(repository.findValue("security.jwt.secret")).thenReturn(Optional.of("db-secret"));

        assertEquals("db-secret", service.value(IdentityConfigKey.JWT_SECRET));
    }

    @Test
    void v1StyleRawAdminPasswordInjectionIsEncodedAtRuntime() {
        environment.setProperty("security.admin.password", "Admin123");

        String passwordHash = service.value(IdentityConfigKey.ADMIN_PASSWORD);

        assertTrue(passwordEncoder.matches("Admin123", passwordHash));
        verifyNoInteractions(repository);
    }

    @Test
    void v1StyleK8sRawAdminPasswordInjectionIsEncodedAtRuntime() {
        environment.setProperty("SECURITY_ADMIN_PASSWORD", "Admin123");

        String passwordHash = service.value(IdentityConfigKey.ADMIN_PASSWORD);

        assertTrue(passwordEncoder.matches("Admin123", passwordHash));
        verifyNoInteractions(repository);
    }

    @Test
    void adminPasswordHashInjectionIsUsedAsIs() {
        String encoded = passwordEncoder.encode("Admin123");
        environment.setProperty("app.identity.admin.password-hash", encoded);

        assertEquals(encoded, service.value(IdentityConfigKey.ADMIN_PASSWORD));
        verifyNoInteractions(repository);
    }

    @Test
    void updateValuePublishesConfigChangedEventForSideWrite() {
        when(repository.upsertValue("security.jwt.iat-floor", "123")).thenReturn(5L);

        service.updateValue(IdentityConfigKey.JWT_IAT_FLOOR, "123");

        verify(repository).upsertValue("security.jwt.iat-floor", "123");
        verify(changePublisher).publish("security.jwt.iat-floor", SystemConfigChangedEvent.Action.UPDATED, "DB", 5L, 4L);
    }
}
