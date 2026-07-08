package com.prodigalgal.ircs.metadata.provider.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.DistributedLockProfiles;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.lock.TokenBucketReservation;
import com.prodigalgal.ircs.common.lock.TokenBucketReservationRequest;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TmdbRateLimiterTest {

    @Test
    void usesCredentialTokenBucketProfileForTmdbQuota() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TokenBucketReservation.allowed(
                "traffic:limit:cred:credential-1",
                29,
                Instant.parse("2026-06-11T12:00:00Z")));
        TmdbRateLimiter limiter = new TmdbRateLimiter(lockManager, new TmdbProviderProperties());
        UUID credentialId = UUID.randomUUID();

        limiter.acquire(new TmdbCredential(credentialId, "secret", 30, "MINUTE", 0));

        assertThat(lockManager.lastTokenBucketRequest).isNotNull();
        assertThat(lockManager.lastTokenBucketRequest.key()).isEqualTo("traffic:limit:cred:" + credentialId);
        assertThat(lockManager.lastTokenBucketRequest.capacity()).isEqualTo(30);
        assertThat(lockManager.lastTokenBucketRequest.refillTokens()).isEqualTo(30);
        assertThat(lockManager.lastTokenBucketRequest.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
        assertThat(lockManager.lastTokenBucketRequest.permits()).isEqualTo(1);
    }

    @Test
    void throwsRetryablePoolExhaustedWhenTokenBucketRejects() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TokenBucketReservation.rejected(
                "traffic:limit:cred:credential-1",
                0,
                Duration.ofSeconds(6),
                Instant.parse("2026-06-11T12:00:00Z")));
        TmdbProviderProperties properties = new TmdbProviderProperties();
        properties.setMaxWait(Duration.ofSeconds(5));
        TmdbRateLimiter limiter = new TmdbRateLimiter(lockManager, properties);

        assertThatThrownBy(() -> limiter.acquire(new TmdbCredential(UUID.randomUUID(), "secret", 30, "MINUTE", 0)))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .hasMessageContaining("TMDB credential pool saturated");
    }

    private static class FakeDistributedLockManager implements DistributedLockManager {

        private final TokenBucketReservation tokenBucketReservation;
        private TokenBucketReservationRequest lastTokenBucketRequest;

        private FakeDistributedLockManager(TokenBucketReservation tokenBucketReservation) {
            this.tokenBucketReservation = tokenBucketReservation;
        }

        @Override
        public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
            return DistributedLockProfiles.profileFor(businessType);
        }

        @Override
        public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
            this.lastTokenBucketRequest = request;
            return tokenBucketReservation;
        }

        @Override
        public Optional<ClusterLease> tryAcquire(String name, String ownerId, Duration ttl) {
            return Optional.empty();
        }

        @Override
        public boolean renew(ClusterLease lease, Duration ttl) {
            return false;
        }

        @Override
        public boolean release(ClusterLease lease) {
            return false;
        }
    }
}
