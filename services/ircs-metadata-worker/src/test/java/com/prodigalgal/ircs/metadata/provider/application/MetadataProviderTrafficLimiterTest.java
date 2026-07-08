package com.prodigalgal.ircs.metadata.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.lease.ClusterLease;
import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.DistributedLockProfiles;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.lock.TokenBucketReservation;
import com.prodigalgal.ircs.common.lock.TokenBucketReservationRequest;
import com.prodigalgal.ircs.metadata.config.SystemConfigRepository;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MetadataProviderTrafficLimiterTest {

    @Test
    void reservesProviderTimeSliceUsingEgressIdentity() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TimeSliceReservation.reserved(
                "traffic:limit:Provider:Metadata:Ip:203.0.113.10:DOUBAN",
                Duration.ZERO,
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:01Z")));
        MetadataProviderTrafficLimiter limiter = new MetadataProviderTrafficLimiter(
                lockManager,
                null,
                true,
                "203.0.113.10",
                1500,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireProviderSlot("DOUBAN");

        assertThat(lockManager.lastTimeSliceRequest.key())
                .isEqualTo("traffic:limit:Provider:Metadata:Ip:203.0.113.10:DOUBAN");
        assertThat(lockManager.lastTimeSliceRequest.gap()).isEqualTo(Duration.ofMillis(1500));
        assertThat(lockManager.lastTimeSliceRequest.maxWait()).isEqualTo(Duration.ofMinutes(2));
        assertThat(lockManager.lastTimeSliceRequest.ttl()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsWhenProviderQueueWaitExceedsMaxWait() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TimeSliceReservation.rejected(
                "traffic:limit:Provider:Metadata:Ip:203.0.113.10:ROTTEN_TOMATOES",
                Duration.ofMinutes(3),
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:03:00Z")));
        MetadataProviderTrafficLimiter limiter = new MetadataProviderTrafficLimiter(
                lockManager,
                null,
                true,
                "203.0.113.10",
                1500,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        assertThatThrownBy(() -> limiter.acquireProviderSlot("ROTTEN_TOMATOES"))
                .isInstanceOf(MetadataProviderRetryableException.class)
                .hasMessageContaining("Metadata provider traffic saturated");
    }

    @Test
    void usesDynamicSystemConfigAheadOfDeploymentDefaults() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TimeSliceReservation.reserved(
                "traffic:limit:Provider:Metadata:Ip:203.0.113.10:TMDB",
                Duration.ZERO,
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:01Z")));
        SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);
        when(repository.findValue("app.metadata.public-traffic.enabled")).thenReturn(Optional.of("true"));
        when(repository.findValue("app.metadata.public-traffic.default-gap-ms")).thenReturn(Optional.of("150"));
        when(repository.findValue("app.metadata.public-traffic.max-wait")).thenReturn(Optional.of("PT3M"));
        when(repository.findValue("app.metadata.public-traffic.ttl")).thenReturn(Optional.of("PT20M"));
        MetadataProviderTrafficLimiter limiter = new MetadataProviderTrafficLimiter(
                lockManager,
                provider(repository),
                true,
                "203.0.113.10",
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireProviderSlot("TMDB");

        assertThat(lockManager.lastTimeSliceRequest.gap()).isEqualTo(Duration.ofMillis(150));
        assertThat(lockManager.lastTimeSliceRequest.maxWait()).isEqualTo(Duration.ofMinutes(3));
        assertThat(lockManager.lastTimeSliceRequest.ttl()).isEqualTo(Duration.ofMinutes(20));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static class FakeDistributedLockManager implements DistributedLockManager {

        private final TimeSliceReservation reservation;
        private TimeSliceReservationRequest lastTimeSliceRequest;

        private FakeDistributedLockManager(TimeSliceReservation reservation) {
            this.reservation = reservation;
        }

        @Override
        public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
            return DistributedLockProfiles.profileFor(businessType);
        }

        @Override
        public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
            this.lastTimeSliceRequest = request;
            return reservation;
        }

        @Override
        public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
            throw new UnsupportedOperationException("not used by metadata provider limiter");
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
