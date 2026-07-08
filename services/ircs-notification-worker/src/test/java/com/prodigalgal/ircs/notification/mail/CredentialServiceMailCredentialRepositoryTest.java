package com.prodigalgal.ircs.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CredentialServiceMailCredentialRepositoryTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final NotificationMailProperties properties = properties();
    private final CredentialServiceMailCredentialRepository repository =
            new CredentialServiceMailCredentialRepository(
                    new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport),
                    new ObjectMapper(),
                    properties);

    @Test
    void springConfigurationCreatesRepositoryWithoutDefaultConstructorOrAutowiredSelection() {
        new ApplicationContextRunner()
                .withUserConfiguration(NotificationMailCredentialConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NotificationMailProperties.class, this::properties)
                .run(context -> assertThat(context)
                        .hasSingleBean(CredentialServiceMailCredentialRepository.class));
    }

    @Test
    void leasesMailCredentialFromCredentialServiceAndFiltersMissingPassword() {
        properties.getCredentialService().setLeaseLimit(2);
        properties.getCredentialService().setRequestTimeout(Duration.ofSeconds(4));
        transport.enqueue(response(200, """
                [{
                  "id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
                  "provider":"MAIL",
                  "name":"missing-password",
                  "secretPayload":{"username":"missing@example.invalid"},
                  "priority":1
                },{
                  "id":"de0a6fd9-f07d-4201-bf92-279b6c9f099d",
                  "provider":"MAIL",
                  "name":"dev-mail",
                  "secretPayload":{
                    "username":"mail@example.invalid",
                    "password":"secret",
                    "smtp_host":"smtp.mail.example.invalid",
                    "smtp_port":"587",
                    "smtp_protocol":"smtp",
                    "smtp_auth":"true",
                    "smtp_starttls_enabled":"true",
                    "smtp_ssl_enabled":"false",
                    "smtp_timeout_ms":"7000"
                  },
                  "priority":9,
                  "rateLimit":30,
                  "rateLimitUnit":"MINUTE",
                  "dayLimit":500,
                  "monthLimit":5000
                }]
                """));

        MailCredential credential = repository.leaseRequired();

        assertEquals("de0a6fd9-f07d-4201-bf92-279b6c9f099d", credential.id().toString());
        assertEquals("mail@example.invalid", credential.username());
        assertEquals("secret", credential.password());
        assertEquals("smtp.mail.example.invalid", credential.smtpHost());
        assertEquals(587, credential.smtpPort());
        assertEquals("smtp", credential.smtpProtocol());
        assertEquals(Boolean.TRUE, credential.smtpAuth());
        assertEquals(Boolean.TRUE, credential.smtpStarttlsEnabled());
        assertEquals(Boolean.FALSE, credential.smtpSslEnabled());
        assertEquals(7000, credential.smtpTimeoutMs());
        assertEquals(30, credential.rateLimit());
        assertEquals("MINUTE", credential.rateLimitUnit());
        assertEquals(500L, credential.dayLimit());
        assertEquals(5000L, credential.monthLimit());
        OutboundHttpRequest request = transport.requests.getFirst();
        assertThat(request.uri()).isEqualTo(URI.create(
                "http://credential-service/internal/credentials/providers/MAIL/leases?requiredPayloadKey=username&limit=2"));
        assertThat(request.headers())
                .containsEntry("X-IRCS-SERVICE-ID", "notification-worker")
                .containsEntry("X-IRCS-SERVICE-TOKEN", "internal-token")
                .containsEntry("X-IRCS-SERVICE-SCOPES", "credential:lease")
                .doesNotContainKey("X-IRCS-INTERNAL-TOKEN");
        assertThat(request.policy().type().name()).isEqualTo("INTERNAL_SERVICE");
        assertThat(request.policy().timeout()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void rotatesAcrossAvailableMailCredentialPool() {
        transport.enqueue(response(200, """
                [{
                  "id":"11111111-1111-4111-8111-111111111111",
                  "provider":"MAIL",
                  "name":"mail-a",
                  "secretPayload":{"username":"a@example.invalid","password":"secret-a"}
                },{
                  "id":"22222222-2222-4222-8222-222222222222",
                  "provider":"MAIL",
                  "name":"mail-b",
                  "secretPayload":{"username":"b@example.invalid","password":"secret-b"}
                }]
                """));
        transport.enqueue(response(200, """
                [{
                  "id":"11111111-1111-4111-8111-111111111111",
                  "provider":"MAIL",
                  "name":"mail-a",
                  "secretPayload":{"username":"a@example.invalid","password":"secret-a"}
                },{
                  "id":"22222222-2222-4222-8222-222222222222",
                  "provider":"MAIL",
                  "name":"mail-b",
                  "secretPayload":{"username":"b@example.invalid","password":"secret-b"}
                }]
                """));

        assertEquals("a@example.invalid", repository.leaseRequired().username());
        assertEquals("b@example.invalid", repository.leaseRequired().username());
    }

    @Test
    void missingCredentialThrowsLeaseException() {
        transport.enqueue(response(200, """
                [{
                  "id":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
                  "provider":"MAIL",
                  "name":"missing-password",
                  "secretPayload":{"username":"missing@example.invalid"},
                  "priority":1
                }]
                """));

        MailCredentialLeaseException thrown = assertThrows(
                MailCredentialLeaseException.class,
                repository::leaseRequired);

        assertEquals("MAIL credential is not available", thrown.getMessage());
    }

    @Test
    void credentialServiceFailureThrowsLeaseExceptionWithoutSecretLogging() {
        transport.enqueue(response(503, "temporary unavailable"));
        transport.enqueue(response(503, "temporary unavailable"));

        MailCredentialLeaseException thrown = assertThrows(
                MailCredentialLeaseException.class,
                repository::leaseRequired);

        assertEquals("Unable to lease MAIL credentials from credential-service", thrown.getMessage());
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    void invalidInternalBaseUrlFailsBeforeTransportSend() {
        properties.getCredentialService().setBaseUrl("http://token@credential-service");

        assertThatThrownBy(repository::leaseRequired)
                .isInstanceOf(MailCredentialLeaseException.class)
                .hasMessage("Unable to lease MAIL credentials from credential-service");
        assertThat(transport.requests).isEmpty();
    }

    private NotificationMailProperties properties() {
        NotificationMailProperties properties = new NotificationMailProperties();
        properties.getCredentialService().setBaseUrl("http://credential-service");
        properties.getCredentialService().setToken("internal-token");
        return properties;
    }

    private OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        @Override
        public List<InetAddress> resolve(String host) {
            throw new AssertionError("INTERNAL_SERVICE must not perform public DNS SSRF resolution");
        }
    }

    private static final class FakeTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final Queue<Object> responses = new ArrayDeque<>();

        void enqueue(Object response) {
            responses.add(response);
        }

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException {
            requests.add(request);
            Object next = responses.remove();
            if (next instanceof IOException ex) {
                throw ex;
            }
            return (OutboundHttpResponse) next;
        }
    }
}
