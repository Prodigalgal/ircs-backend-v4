package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MailSendRateLimiterTest {

    @Test
    void selectsNextCredentialWhenFirstCredentialExhaustsDailyQuota() {
        NotificationMailConfigValues configValues = configValues(0, 0, 0);
        FakeUsageRepository usageRepository = new FakeUsageRepository();
        MutableClock clock = new MutableClock();
        MailCredential limited = credential("11111111-1111-4111-8111-111111111111", 0, null, 1L, 0L);
        MailCredential available = credential("22222222-2222-4222-8222-222222222222", 0, null, 0L, 0L);
        usageRepository.counts = Map.of(limited.id(), 1L);
        MailSendRateLimiter limiter = new MailSendRateLimiter(
                configValues,
                usageRepository,
                provider(clock),
                provider((MailSendRateLimiter.Sleeper) clock::advance));

        MailCredential selected = limiter.selectCredential(List.of(limited, available));

        assertEquals(available.id(), selected.id());
        assertEquals(0L, clock.sleptMs.get());
    }

    @Test
    void waitsForSingleCredentialRateLimitWindowWhenEveryCandidateIsLimited() {
        NotificationMailConfigValues configValues = configValues(0, 0, 0);
        MutableClock clock = new MutableClock();
        MailCredential credential = credential("11111111-1111-4111-8111-111111111111", 1, "SECOND", 0L, 0L);
        MailSendRateLimiter limiter = new MailSendRateLimiter(
                configValues,
                new FakeUsageRepository(),
                provider(clock),
                provider((MailSendRateLimiter.Sleeper) clock::advance));

        assertEquals(credential.id(), limiter.selectCredential(List.of(credential)).id());
        assertEquals(credential.id(), limiter.selectCredential(List.of(credential)).id());

        assertEquals(1000L, clock.sleptMs.get());
    }

    @Test
    void globalDelayAppliesWhenNoCredentialPoolIsUsed() {
        NotificationMailConfigValues configValues = configValues(500, 500, 0);
        MutableClock clock = new MutableClock();
        MailSendRateLimiter limiter = new MailSendRateLimiter(
                configValues,
                new FakeUsageRepository(),
                provider(clock),
                provider((MailSendRateLimiter.Sleeper) clock::advance));

        limiter.awaitGlobalPermit();
        limiter.awaitGlobalPermit();

        assertEquals(500L, clock.sleptMs.get());
    }

    @Test
    void springConstructorUsesSinglePublicInjectionPath() throws NoSuchMethodException {
        assertEquals(
                1,
                MailSendRateLimiter.class.getDeclaredConstructors().length);
        assertEquals(
                MailSendRateLimiter.class,
                MailSendRateLimiter.class
                        .getDeclaredConstructor(
                                NotificationMailConfigValues.class,
                                MailCredentialUsageRepository.class,
                                ObjectProvider.class,
                                ObjectProvider.class)
                        .getDeclaringClass());
    }

    private static NotificationMailConfigValues configValues(int minMs, int maxMs, int maxWaitMs) {
        NotificationMailConfigValues values = org.mockito.Mockito.mock(NotificationMailConfigValues.class);
        when(values.rateLimitMinMs()).thenReturn(minMs);
        when(values.rateLimitMaxMs()).thenReturn(maxMs);
        when(values.rateLimitMaxWaitMs()).thenReturn(maxWaitMs);
        return values;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static MailCredential credential(
            String id,
            Integer rateLimit,
            String rateLimitUnit,
            Long dayLimit,
            Long monthLimit) {
        return new MailCredential(
                UUID.fromString(id),
                id + "@example.invalid",
                "secret",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                rateLimit,
                rateLimitUnit,
                dayLimit,
                monthLimit);
    }

    private static final class FakeUsageRepository extends MailCredentialUsageRepository {

        private Map<UUID, Long> counts = Map.of();

        private FakeUsageRepository() {
            super("", null, null);
        }

        @Override
        long countSentSince(UUID credentialId, Instant since) {
            return counts.getOrDefault(credentialId, 0L);
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicLong millis = new AtomicLong();
        private final AtomicLong sleptMs = new AtomicLong();

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
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            return millis.get();
        }

        private void advance(long deltaMs) {
            sleptMs.addAndGet(deltaMs);
            millis.addAndGet(deltaMs);
        }
    }
}
