package com.prodigalgal.ircs.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.security.IrcsAuthException;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminApiTokenServiceTest {

    private final FakeRepository repository = new FakeRepository();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-19T10:00:00Z"));
    private final AdminApiTokenService service = new AdminApiTokenService(
            repository,
            clock,
            provider(new SecureRandom(new byte[]{1, 2, 3, 4})),
            "PT30S",
            "PT5M");

    @Test
    void createStoresOnlyHashAndReturnsRawTokenOnce() {
        AdminApiTokenDtos.CreatedResponse created = service.create("  deploy bot  ", "admin");

        assertThat(created.name()).isEqualTo("deploy bot");
        assertThat(created.token()).startsWith(AdminApiTokenService.TOKEN_PREFIX);
        assertThat(created.tokenPrefix()).isEqualTo(created.token().substring(0, 24));
        assertThat(repository.records).hasSize(1);
        AdminApiTokenRecord stored = repository.records.getFirst();
        assertThat(stored.tokenHash()).hasSize(64);
        assertThat(stored.tokenHash()).doesNotContain(created.token());
        assertThat(stored.tokenPrefix()).isEqualTo(created.tokenPrefix());
        assertThat(service.list().getFirst().tokenPrefix()).isEqualTo(created.tokenPrefix());
    }

    @Test
    void authenticatesBearerAndMarksAdminPrincipal() {
        AdminApiTokenDtos.CreatedResponse created = service.create("script", "admin");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + created.token());

        var principal = service.authenticateIfPresent(request).orElseThrow();

        assertThat(principal.role()).isEqualTo(IrcsPermissions.ROLE_ADMIN);
        assertThat(principal.subject()).isEqualTo("api-token:" + created.tokenPrefix());
        assertThat(principal.hasPermission(IrcsPermissions.CONTENT_WRITE)).isTrue();
        assertThat(repository.touchCount).isEqualTo(1);
    }

    @Test
    void authenticatesDedicatedHeaderAndUsesPositiveCache() {
        AdminApiTokenDtos.CreatedResponse created = service.create("script", "admin");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminApiTokenService.HEADER_API_TOKEN, created.token());

        service.authenticateIfPresent(request).orElseThrow();
        service.authenticateIfPresent(request).orElseThrow();

        assertThat(repository.findCount).isEqualTo(1);
        assertThat(repository.touchCount).isEqualTo(1);
    }

    @Test
    void authenticatesQueryTokenForNativeEventSource() {
        AdminApiTokenDtos.CreatedResponse created = service.create("stream", "admin");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/stream/metrics");
        request.addHeader("Accept", "text/event-stream");
        request.setParameter("token", created.token());

        var principal = service.authenticateIfPresent(request).orElseThrow();

        assertThat(principal.role()).isEqualTo(IrcsPermissions.ROLE_ADMIN);
        assertThat(principal.subject()).isEqualTo("api-token:" + created.tokenPrefix());
    }

    @Test
    void ignoresQueryTokenOnRegularRequests() {
        AdminApiTokenDtos.CreatedResponse created = service.create("regular", "admin");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/raw-videos");
        request.setParameter("token", created.token());

        assertThat(service.authenticateIfPresent(request)).isEmpty();
    }

    @Test
    void revokedTokenFailsAfterCacheExpires() {
        AdminApiTokenDtos.CreatedResponse created = service.create("script", "admin");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminApiTokenService.HEADER_API_TOKEN, created.token());
        service.authenticateIfPresent(request).orElseThrow();

        service.revoke(created.id(), "admin");
        clock.advance(Duration.ofSeconds(31));

        assertThatThrownBy(() -> service.authenticateIfPresent(request))
                .isInstanceOf(IrcsAuthException.class)
                .satisfies(ex -> assertThat(((IrcsAuthException) ex).reason())
                        .isEqualTo(IrcsAuthException.Reason.INVALID));
    }

    @Test
    void ignoresNonPatBearerSoJwtCanHandleIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer jwt-token");

        assertThat(service.authenticateIfPresent(request)).isEmpty();
    }

    private static final class FakeRepository implements AdminApiTokenRepository {

        private final List<AdminApiTokenRecord> records = new ArrayList<>();
        private int findCount;
        private int touchCount;

        @Override
        public void insert(AdminApiTokenRecord record) {
            records.add(record);
        }

        @Override
        public List<AdminApiTokenRecord> list() {
            return List.copyOf(records);
        }

        @Override
        public Optional<AdminApiTokenRecord> findActiveByHash(String tokenHash, Instant now) {
            findCount++;
            return records.stream()
                    .filter(record -> record.tokenHash().equals(tokenHash))
                    .filter(record -> "ACTIVE".equals(record.status()))
                    .filter(record -> record.expiresAt() == null || record.expiresAt().isAfter(now))
                    .findFirst();
        }

        @Override
        public boolean revoke(UUID id, String revokedBy, Instant now) {
            for (int i = 0; i < records.size(); i++) {
                AdminApiTokenRecord record = records.get(i);
                if (record.id().equals(id) && "ACTIVE".equals(record.status())) {
                    records.set(i, new AdminApiTokenRecord(
                            record.id(),
                            record.name(),
                            record.tokenPrefix(),
                            record.tokenHash(),
                            "REVOKED",
                            record.createdBy(),
                            record.createdAt(),
                            record.lastUsedAt(),
                            now,
                            revokedBy,
                            record.expiresAt()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public void touch(UUID id, Instant now) {
            touchCount++;
            for (int i = 0; i < records.size(); i++) {
                AdminApiTokenRecord record = records.get(i);
                if (record.id().equals(id)) {
                    records.set(i, new AdminApiTokenRecord(
                            record.id(),
                            record.name(),
                            record.tokenPrefix(),
                            record.tokenHash(),
                            record.status(),
                            record.createdBy(),
                            record.createdAt(),
                            now,
                            record.revokedAt(),
                            record.revokedBy(),
                            record.expiresAt()));
                    return;
                }
            }
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
