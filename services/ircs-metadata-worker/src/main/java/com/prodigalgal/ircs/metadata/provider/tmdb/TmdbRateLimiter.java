package com.prodigalgal.ircs.metadata.provider.tmdb;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.TokenBucketReservation;
import com.prodigalgal.ircs.common.lock.TokenBucketReservationRequest;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TmdbRateLimiter {

    private static final Duration MIN_TTL = Duration.ofMinutes(2);

    private final DistributedLockManager lockManager;
    private final TmdbProviderProperties properties;

    public void acquire(TmdbCredential credential) {
        int rateLimit = rateLimit(credential);
        if (rateLimit <= 0) {
            return;
        }
        Duration refillPeriod = refillPeriod(credential);
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.CREDENTIAL_API_CALL);
        String key = profile.keyPrefix() + credential.id();
        Duration maxWait = properties.getMaxWait();
        Duration ttl = max(MIN_TTL, refillPeriod.multipliedBy(2));

        while (true) {
            TokenBucketReservation reservation = lockManager.reserveTokenBucket(new TokenBucketReservationRequest(
                    key,
                    rateLimit,
                    rateLimit,
                    refillPeriod,
                    1,
                    maxWait,
                    ttl));
            if (reservation.allowed()) {
                return;
            }
            if (reservation.rejected()) {
                throw new MetadataProviderRetryableException(
                        "POOL_EXHAUSTED",
                        "TMDB credential pool saturated. Min wait: " + reservation.retryAfter().toMillis() + "ms");
            }
            sleep(Math.max(1L, reservation.retryAfter().toMillis()));
        }
    }

    private int rateLimit(TmdbCredential credential) {
        return credential.rateLimit() == null || credential.rateLimit() <= 0
                ? properties.getDefaultRateLimit()
                : credential.rateLimit();
    }

    private Duration refillPeriod(TmdbCredential credential) {
        String unit = credential.rateLimitUnit() == null || credential.rateLimitUnit().isBlank()
                ? properties.getDefaultRateLimitUnit()
                : credential.rateLimitUnit();
        return "MINUTE".equalsIgnoreCase(unit) ? Duration.ofMinutes(1) : Duration.ofSeconds(1);
    }

    private Duration max(Duration left, Duration right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private void sleep(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MetadataProviderRetryableException("INTERRUPTED", "Interrupted while waiting for TMDB rate limit", ex);
        }
    }
}
