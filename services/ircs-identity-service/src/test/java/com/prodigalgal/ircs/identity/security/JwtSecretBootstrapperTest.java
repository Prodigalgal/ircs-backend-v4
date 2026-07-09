package com.prodigalgal.ircs.identity.security;



import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.application.IdentityConfigService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JwtSecretBootstrapperTest {

    private final IdentityConfigService configService = org.mockito.Mockito.mock(IdentityConfigService.class);
    private final JwtSecretBootstrapper bootstrapper = new JwtSecretBootstrapper(configService);

    @Test
    void startupGeneratesRuntimeSecretAndPersistsIt() {
        bootstrapper.run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<IdentityConfigKey, String>> valuesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> persistedCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).installRuntimeValues(valuesCaptor.capture());
        verify(configService).updateValue(org.mockito.Mockito.eq(IdentityConfigKey.JWT_SECRET), persistedCaptor.capture());

        String runtimeSecret = valuesCaptor.getValue().get(IdentityConfigKey.JWT_SECRET);
        assertEquals(runtimeSecret, persistedCaptor.getValue());
        assertTrue(runtimeSecret.getBytes(StandardCharsets.UTF_8).length >= 32);
    }

    @Test
    void startupKeepsConfiguredSecret() {
        when(configService.value(IdentityConfigKey.JWT_SECRET))
                .thenReturn("configured-jwt-secret-change-me-32-bytes");

        bootstrapper.run(null);

        verify(configService, never()).installRuntimeValues(org.mockito.ArgumentMatchers.anyMap());
        verify(configService, never()).updateValue(
                org.mockito.Mockito.eq(IdentityConfigKey.JWT_SECRET),
                org.mockito.ArgumentMatchers.anyString());
    }
}
