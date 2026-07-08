package com.prodigalgal.ircs.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class RuntimeCredentialInitializerTest {

    private final CredentialService credentialService = org.mockito.Mockito.mock(CredentialService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path tempDir;

    @Test
    void importsV1EnvCredentialsWithMatchingDefaults() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("SPRING_AI_OPENAI_API_KEY", "sk-test-openai")
                .withProperty("SPRING_AI_OPENAI_BASE_URL", "https://llm.example.test/v1/")
                .withProperty("APP_METADATA_TMDB_API_KEY", "tmdb-test")
                .withProperty("APP_MAIL_USERNAME", "mail@example.test")
                .withProperty("APP_MAIL_PASSWORD", "mail-password")
                .withProperty("APP_MAIL_HOST", "smtp.mail.example.test")
                .withProperty("APP_MAIL_PORT", "587")
                .withProperty("APP_MAIL_STARTTLS_ENABLED", "true")
                .withProperty("APP_MAIL_SSL_ENABLED", "false")
                .withProperty("APP_MAIL_TIMEOUT", "7000")
                .withProperty("APP_STORAGE_R2_ACCOUNT_ID", "r2-account")
                .withProperty("APP_STORAGE_R2_ACCESS_KEY", "r2-access")
                .withProperty("APP_STORAGE_R2_SECRET_KEY", "r2-secret");
        when(credentialService.createIfAbsent(any())).thenReturn(true);
        RuntimeCredentialInitializer initializer = RuntimeCredentialInitializer.forTest(
                credentialService,
                env,
                objectMapper,
                true,
                missingFilePath(),
                false);

        RuntimeCredentialInitializer.InitializationResult result = initializer.initialize();

        assertThat(result.envInserted()).isEqualTo(4);
        ArgumentCaptor<CredentialWriteRequest> captor = ArgumentCaptor.forClass(CredentialWriteRequest.class);
        verify(credentialService, org.mockito.Mockito.times(4)).createIfAbsent(captor.capture());
        assertThat(captor.getAllValues()).extracting(CredentialWriteRequest::provider)
                .containsExactly("OPENAI", "TMDB", "MAIL", "R2");
        assertThat(captor.getAllValues()).extracting(CredentialWriteRequest::name)
                .containsExactly(
                        "Env: OpenAI Primary",
                        "Env: TMDB Primary",
                        "Env: Mail Primary",
                        "Env: R2 Primary");

        CredentialWriteRequest openAi = captor.getAllValues().get(0);
        assertThat(openAi.priority()).isEqualTo(100);
        assertThat(openAi.rateLimit()).isEqualTo(15);
        assertThat(openAi.rateLimitUnit()).isEqualTo("MINUTE");
        assertThat(openAi.payload()).containsEntry("base_url", "https://llm.example.test/v1/");

        CredentialWriteRequest tmdb = captor.getAllValues().get(1);
        assertThat(tmdb.rateLimit()).isEqualTo(40);
        assertThat(tmdb.rateLimitUnit()).isEqualTo("MINUTE");

        CredentialWriteRequest mail = captor.getAllValues().get(2);
        assertThat(mail.rateLimit()).isNull();
        assertThat(mail.rateLimitUnit()).isNull();
        assertThat(mail.dayLimit()).isEqualTo(500L);
        assertThat(mail.payload()).containsEntry("smtp_host", "smtp.mail.example.test")
                .containsEntry("smtp_port", 587)
                .containsEntry("smtp_starttls_enabled", true)
                .containsEntry("smtp_ssl_enabled", false)
                .containsEntry("smtp_timeout_ms", 7000);

        CredentialWriteRequest r2 = captor.getAllValues().get(3);
        assertThat(r2.rateLimit()).isNull();
        assertThat(r2.rateLimitUnit()).isNull();
        assertThat(r2.payload().keySet()).containsExactlyInAnyOrder("account_id", "access_key", "secret_key");
    }

    @Test
    void skipsPlaceholderAndBlankEnvCredentials() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("SPRING_AI_OPENAI_API_KEY", "")
                .withProperty("APP_METADATA_TMDB_API_KEY", "YOUR_TMDB_API_KEY_HERE")
                .withProperty("APP_MAIL_USERNAME", "mail@example.test")
                .withProperty("APP_MAIL_PASSWORD", "")
                .withProperty("APP_STORAGE_R2_ACCOUNT_ID", "r2-account")
                .withProperty("APP_STORAGE_R2_ACCESS_KEY", "r2-access");
        RuntimeCredentialInitializer initializer = RuntimeCredentialInitializer.forTest(
                credentialService,
                env,
                objectMapper,
                true,
                missingFilePath(),
                false);

        RuntimeCredentialInitializer.InitializationResult result = initializer.initialize();

        assertThat(result.envInserted()).isZero();
        verify(credentialService, never()).createIfAbsent(any());
    }

    @Test
    void importsConfiguredFileAndDefaultsRateLimitUnitToMinute() throws Exception {
        Path file = tempDir.resolve("credentials.json");
        Files.writeString(file, """
                [
                  {
                    "provider": "TMDB",
                    "name": "File TMDB",
                    "payload": {"api_key": "tmdb-file"},
                    "priority": 7,
                    "rateLimit": 9,
                    "dayLimit": 11,
                    "monthLimit": 12,
                    "remark": "file import"
                  },
                  {
                    "provider": "MAIL",
                    "payload": {"username": "file@example.test", "password": "file-password"},
                    "rateLimit": 0
                  }
                ]
                """);
        when(credentialService.createIfAbsent(any())).thenReturn(true);
        RuntimeCredentialInitializer initializer = RuntimeCredentialInitializer.forTest(
                credentialService,
                new MockEnvironment(),
                objectMapper,
                true,
                file.toString(),
                false);

        RuntimeCredentialInitializer.InitializationResult result = initializer.initialize();

        assertThat(result.fileInserted()).isEqualTo(2);
        ArgumentCaptor<CredentialWriteRequest> captor = ArgumentCaptor.forClass(CredentialWriteRequest.class);
        verify(credentialService, org.mockito.Mockito.times(2)).createIfAbsent(captor.capture());
        CredentialWriteRequest tmdb = captor.getAllValues().get(0);
        assertThat(tmdb.name()).isEqualTo("File TMDB");
        assertThat(tmdb.rateLimit()).isEqualTo(9);
        assertThat(tmdb.rateLimitUnit()).isEqualTo("MINUTE");
        assertThat(tmdb.dayLimit()).isEqualTo(11L);
        assertThat(tmdb.monthLimit()).isEqualTo(12L);
        CredentialWriteRequest mail = captor.getAllValues().get(1);
        assertThat(mail.name()).isEqualTo("MAIL-Init-1");
        assertThat(mail.rateLimit()).isNull();
        assertThat(mail.rateLimitUnit()).isNull();
    }

    @Test
    void importsMailFileCredentialWithBoundSmtpConfig() throws Exception {
        Path file = tempDir.resolve("mail-credentials.json");
        Files.writeString(file, """
                [
                  {
                    "provider": "MAIL",
                    "name": "SMTP Mail",
                    "payload": {
                      "username": "smtp@example.test",
                      "password": "smtp-password",
                      "smtp_host": "smtp.example.test",
                      "smtp_port": 587,
                      "smtp_protocol": "smtp",
                      "smtp_auth": true,
                      "smtp_starttls_enabled": true,
                      "smtp_ssl_enabled": false,
                      "smtp_timeout_ms": 7000
                    }
                  }
                ]
                """);
        when(credentialService.createIfAbsent(any())).thenReturn(true);
        RuntimeCredentialInitializer initializer = RuntimeCredentialInitializer.forTest(
                credentialService,
                new MockEnvironment(),
                objectMapper,
                true,
                file.toString(),
                false);

        RuntimeCredentialInitializer.InitializationResult result = initializer.initialize();

        assertThat(result.fileInserted()).isEqualTo(1);
        ArgumentCaptor<CredentialWriteRequest> captor = ArgumentCaptor.forClass(CredentialWriteRequest.class);
        verify(credentialService).createIfAbsent(captor.capture());
        assertThat(captor.getValue().payload()).containsEntry("smtp_host", "smtp.example.test")
                .containsEntry("smtp_port", 587)
                .containsEntry("smtp_starttls_enabled", true)
                .containsEntry("smtp_ssl_enabled", false);
    }

    @Test
    void duplicateFingerprintSkipsWithoutOverwritingExistingCredential() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("APP_METADATA_TMDB_API_KEY", "tmdb-test");
        when(credentialService.createIfAbsent(any())).thenReturn(false);
        RuntimeCredentialInitializer initializer = RuntimeCredentialInitializer.forTest(
                credentialService,
                env,
                objectMapper,
                true,
                missingFilePath(),
                false);

        RuntimeCredentialInitializer.InitializationResult result = initializer.initialize();

        assertThat(result.envInserted()).isZero();
        verify(credentialService).createIfAbsent(any());
    }

    @Test
    void disabledGateSkipsEnvAndFileWrites() throws Exception {
        Path file = tempDir.resolve("credentials.json");
        Files.writeString(file, """
                [{"provider":"TMDB","payload":{"api_key":"tmdb-file"}}]
                """);
        MockEnvironment env = new MockEnvironment()
                .withProperty("APP_METADATA_TMDB_API_KEY", "tmdb-test");
        RuntimeCredentialInitializer initializer = RuntimeCredentialInitializer.forTest(
                credentialService,
                env,
                objectMapper,
                false,
                file.toString(),
                true);

        RuntimeCredentialInitializer.InitializationResult result = initializer.initialize();

        assertThat(result.envInserted()).isZero();
        assertThat(result.fileInserted()).isZero();
        verify(credentialService, never()).createIfAbsent(any());
    }

    private String missingFilePath() {
        return tempDir.resolve("missing.json").toString();
    }
}
