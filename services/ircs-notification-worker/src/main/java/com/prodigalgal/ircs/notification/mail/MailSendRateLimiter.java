package com.prodigalgal.ircs.notification.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@Slf4j
class MailSendRateLimiter {

    private static final long SECOND_WINDOW_MS = 1_000L;
    private static final long MINUTE_WINDOW_MS = 60_000L;

    private final NotificationMailConfigValues configValues;
    private final MailCredentialUsageRepository usageRepository;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Object monitor = new Object();
    private final Map<UUID, Deque<Long>> credentialWindows = new HashMap<>();
    private long nextGlobalPermitAtMs;
    MailSendRateLimiter(
            NotificationMailConfigValues configValues,
            MailCredentialUsageRepository usageRepository,
            ObjectProvider<Clock> clockProvider,
            ObjectProvider<Sleeper> sleeperProvider) {
        this.configValues = configValues;
        this.usageRepository = usageRepository;
        Clock providedClock = clockProvider == null ? null : clockProvider.getIfAvailable();
        Sleeper providedSleeper = sleeperProvider == null ? null : sleeperProvider.getIfAvailable();
        this.clock = providedClock == null ? Clock.systemUTC() : providedClock;
        this.sleeper = providedSleeper == null ? Thread::sleep : providedSleeper;
    }

    void awaitGlobalPermit() {
        awaitPermit(null);
    }

    MailCredential selectCredential(List<MailCredential> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new MailCredentialLeaseException("MAIL credential is not available");
        }
        while (true) {
            long now = clock.millis();
            CredentialPermit best = candidates.stream()
                    .map(credential -> evaluate(credential, now))
                    .filter(CredentialPermit::available)
                    .min(Comparator.comparingLong(CredentialPermit::waitMs))
                    .orElseThrow(() -> new MailCredentialLeaseException("MAIL credential quota exhausted"));
            long waitMs = Math.max(globalWaitMs(now), best.waitMs());
            if (waitMs <= 0) {
                synchronized (monitor) {
                    long lockedNow = clock.millis();
                    CredentialPermit locked = evaluate(best.credential(), lockedNow);
                    long lockedWaitMs = Math.max(globalWaitMs(lockedNow), locked.waitMs());
                    if (locked.available() && lockedWaitMs <= 0) {
                        markPermit(best.credential(), lockedNow);
                        return best.credential();
                    }
                    if (!locked.available()) {
                        throw new MailCredentialLeaseException("MAIL credential quota exhausted");
                    }
                    waitMs = lockedWaitMs;
                }
            }
            sleepWithinConfiguredBound(waitMs);
        }
    }

    private void awaitPermit(MailCredential credential) {
        while (true) {
            long now = clock.millis();
            long waitMs = globalWaitMs(now);
            if (credential != null) {
                CredentialPermit permit = evaluate(credential, now);
                if (!permit.available()) {
                    throw new MailCredentialLeaseException("MAIL credential quota exhausted");
                }
                waitMs = Math.max(waitMs, permit.waitMs());
            }
            if (waitMs <= 0) {
                synchronized (monitor) {
                    long lockedNow = clock.millis();
                    long lockedWaitMs = globalWaitMs(lockedNow);
                    if (credential != null) {
                        CredentialPermit permit = evaluate(credential, lockedNow);
                        if (!permit.available()) {
                            throw new MailCredentialLeaseException("MAIL credential quota exhausted");
                        }
                        lockedWaitMs = Math.max(lockedWaitMs, permit.waitMs());
                    }
                    if (lockedWaitMs <= 0) {
                        markPermit(credential, lockedNow);
                        return;
                    }
                    waitMs = lockedWaitMs;
                }
            }
            sleepWithinConfiguredBound(waitMs);
        }
    }

    private CredentialPermit evaluate(MailCredential credential, long nowMs) {
        if (credential == null) {
            return new CredentialPermit(null, true, 0L);
        }
        if (isDailyQuotaExhausted(credential) || isMonthlyQuotaExhausted(credential)) {
            return new CredentialPermit(credential, false, Long.MAX_VALUE);
        }
        long waitMs = credentialWindowWaitMs(credential, nowMs);
        return new CredentialPermit(credential, true, waitMs);
    }

    private boolean isDailyQuotaExhausted(MailCredential credential) {
        long limit = positiveLong(credential.dayLimit());
        if (limit <= 0) {
            return false;
        }
        Instant dayStart = LocalDate.now(clock)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        return usageRepository.countSentSince(credential.id(), dayStart) >= limit;
    }

    private boolean isMonthlyQuotaExhausted(MailCredential credential) {
        long limit = positiveLong(credential.monthLimit());
        if (limit <= 0) {
            return false;
        }
        Instant monthStart = YearMonth.now(clock)
                .atDay(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        return usageRepository.countSentSince(credential.id(), monthStart) >= limit;
    }

    private long credentialWindowWaitMs(MailCredential credential, long nowMs) {
        int limit = positiveInt(credential.rateLimit());
        if (limit <= 0 || credential.id() == null) {
            return 0L;
        }
        long windowMs = rateLimitWindowMs(credential.rateLimitUnit());
        synchronized (monitor) {
            Deque<Long> window = credentialWindows.computeIfAbsent(credential.id(), ignored -> new ArrayDeque<>());
            purgeExpired(window, nowMs, windowMs);
            if (window.size() < limit) {
                return 0L;
            }
            Long oldest = window.peekFirst();
            if (oldest == null) {
                return 0L;
            }
            return Math.max(0L, oldest + windowMs - nowMs);
        }
    }

    private void markPermit(MailCredential credential, long nowMs) {
        nextGlobalPermitAtMs = nowMs + nextGlobalDelayMs();
        if (credential == null || credential.id() == null || positiveInt(credential.rateLimit()) <= 0) {
            return;
        }
        long windowMs = rateLimitWindowMs(credential.rateLimitUnit());
        Deque<Long> window = credentialWindows.computeIfAbsent(credential.id(), ignored -> new ArrayDeque<>());
        purgeExpired(window, nowMs, windowMs);
        window.addLast(nowMs);
    }

    private long globalWaitMs(long nowMs) {
        synchronized (monitor) {
            return Math.max(0L, nextGlobalPermitAtMs - nowMs);
        }
    }

    private long nextGlobalDelayMs() {
        int min = configValues.rateLimitMinMs();
        int max = configValues.rateLimitMaxMs();
        if (max <= 0) {
            return 0L;
        }
        if (max == min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void sleepWithinConfiguredBound(long waitMs) {
        if (waitMs <= 0) {
            return;
        }
        int maxWaitMs = configValues.rateLimitMaxWaitMs();
        if (maxWaitMs > 0 && waitMs > maxWaitMs) {
            throw new MailCredentialLeaseException("MAIL credential rate limit wait exceeds configured max wait");
        }
        try {
            sleeper.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MailCredentialLeaseException("MAIL rate limit wait interrupted", ex);
        }
    }

    private static void purgeExpired(Deque<Long> timestamps, long nowMs, long windowMs) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= nowMs - windowMs) {
            timestamps.removeFirst();
        }
    }

    private static long rateLimitWindowMs(String unit) {
        if (unit == null) {
            return MINUTE_WINDOW_MS;
        }
        return switch (unit.trim().toUpperCase(Locale.ROOT)) {
            case "SECOND" -> SECOND_WINDOW_MS;
            case "MINUTE" -> MINUTE_WINDOW_MS;
            default -> MINUTE_WINDOW_MS;
        };
    }

    private static int positiveInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static long positiveLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record CredentialPermit(MailCredential credential, boolean available, long waitMs) {
    }
}
