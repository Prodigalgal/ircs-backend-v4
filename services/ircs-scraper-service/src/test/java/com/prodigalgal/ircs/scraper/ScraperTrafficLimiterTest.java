package com.prodigalgal.ircs.scraper;

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
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScraperTrafficLimiterTest {

    private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String EGRESS_IP = "203.0.113.10";

    @Test
    void reservesDatasourceTimeSliceUsingFixedDelayAsGap() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TimeSliceReservation.reserved(
                sourceKey(),
                Duration.ZERO,
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:01Z")));
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig(Duration.ofMillis(250), Duration.ZERO, Duration.ofMinutes(2)),
                true,
                EGRESS_IP,
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireDataSourceSlot(source(), config(750));

        assertThat(lockManager.timeSliceRequests).hasSize(1);
        TimeSliceReservationRequest sourceRequest = lockManager.timeSliceRequests.getFirst();
        assertThat(sourceRequest.key()).isEqualTo(sourceKey());
        assertThat(sourceRequest.gap()).isEqualTo(Duration.ofMillis(750));
        assertThat(sourceRequest.maxWait()).isEqualTo(Duration.ofMinutes(2));
        assertThat(sourceRequest.ttl()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void usesDefaultGapWhenRequestHasNoFixedDelay() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TimeSliceReservation.reserved(
                sourceKey(),
                Duration.ZERO,
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:01Z")));
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig(Duration.ofMillis(250), Duration.ZERO, Duration.ofMinutes(2)),
                true,
                EGRESS_IP,
                1250,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireDataSourceSlot(source(), config(0));

        assertThat(lockManager.timeSliceRequests.getFirst().gap()).isEqualTo(Duration.ofMillis(1250));
    }

    @Test
    void safetyFloorOverridesTooSmallTaskDelay() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(TimeSliceReservation.reserved(
                sourceKey(),
                Duration.ZERO,
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:01Z")));
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig(Duration.ofMillis(250), Duration.ofMillis(3000), Duration.ofMinutes(2)),
                true,
                EGRESS_IP,
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireDataSourceSlot(source(), config(500));

        assertThat(lockManager.timeSliceRequests.getFirst().gap()).isEqualTo(Duration.ofMillis(3000));
    }

    @Test
    void rejectsWhenDatasourceQueueWaitExceedsMaxWait() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager(
                TimeSliceReservation.rejected(
                        sourceKey(),
                        Duration.ofMinutes(3),
                        Instant.parse("2026-06-11T00:00:00Z"),
                        Instant.parse("2026-06-11T00:03:00Z")));
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig(Duration.ofMillis(250), Duration.ZERO, Duration.ofMinutes(2)),
                true,
                EGRESS_IP,
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        assertThatThrownBy(() -> limiter.acquireDataSourceSlot(source(), config(1000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Traffic limit saturated");
    }

    @Test
    void neverReservesGlobalTimeSliceForCrawlerTraffic() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager();
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig(Duration.ofMillis(250), Duration.ZERO, Duration.ofMinutes(2), true),
                true,
                EGRESS_IP,
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireDataSourceSlot(source(), config(1000));

        assertThat(lockManager.timeSliceRequests).hasSize(1);
        assertThat(lockManager.timeSliceRequests.getFirst().key()).isEqualTo(sourceKey());
    }

    @Test
    void normalizesIpv6EgressIdentityInTrafficKeys() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager();
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig(Duration.ofMillis(250), Duration.ZERO, Duration.ofMinutes(2)),
                true,
                "2001:db8::10",
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireDataSourceSlot(source(), config(1000));

        assertThat(lockManager.timeSliceRequests.getFirst().key())
                .isEqualTo("traffic:limit:DataSource:Scraper:Ip:2001_db8__10:" + SOURCE_ID);
    }

    @Test
    void runtimeUnknownEgressIdentityFallsBackToDeploymentIdentity() {
        FakeDistributedLockManager lockManager = new FakeDistributedLockManager();
        ScraperTrafficConfigValues trafficConfig = trafficConfig(Duration.ofMillis(250), Duration.ZERO, Duration.ofMinutes(2));
        org.mockito.Mockito.when(trafficConfig.egressIdentity(org.mockito.Mockito.anyString())).thenReturn("unknown");
        ScraperTrafficLimiter limiter = limiter(
                lockManager,
                trafficConfig,
                true,
                EGRESS_IP,
                1000,
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));

        limiter.acquireDataSourceSlot(source(), config(1000));

        assertThat(lockManager.timeSliceRequests.getFirst().key()).isEqualTo(sourceKey());
    }

    private DataSourceRecord source() {
        return new DataSourceRecord(
                SOURCE_ID,
                "Fake",
                "https://provider.example.test",
                "/api.php",
                "{}",
                "/detail/{id}",
                "{}",
                "{}");
    }

    private ManualScrapeConfigRequest config(Integer fixedDelayMs) {
        return new ManualScrapeConfigRequest(
                "codex",
                null,
                null,
                1,
                1,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                fixedDelayMs,
                false,
                List.of());
    }

    private ScraperTrafficConfigValues trafficConfig(Duration globalGap, Duration safetyFloor, Duration maxWait) {
        return trafficConfig(globalGap, safetyFloor, maxWait, true);
    }

    private ScraperTrafficConfigValues trafficConfig(
            Duration globalGap,
            Duration safetyFloor,
            Duration maxWait,
            boolean sourceEnabled) {
        ScraperTrafficConfigValues values = org.mockito.Mockito.mock(ScraperTrafficConfigValues.class);
        org.mockito.Mockito.when(values.safetyFloor()).thenReturn(safetyFloor);
        org.mockito.Mockito.when(values.maxWait(org.mockito.Mockito.any())).thenReturn(maxWait);
        org.mockito.Mockito.when(values.sourceEnabled(org.mockito.Mockito.anyBoolean())).thenReturn(sourceEnabled);
        org.mockito.Mockito.when(values.enabled(org.mockito.Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.when(values.egressIdentity(org.mockito.Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.when(values.defaultGap(org.mockito.Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.when(values.limiterMaxWait(org.mockito.Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.when(values.ttl(org.mockito.Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return values;
    }

    private ScraperTrafficLimiter limiter(
            DistributedLockManager lockManager,
            ScraperTrafficConfigValues trafficConfig,
            boolean enabled,
            String egressIp,
            long defaultGapMs,
            Duration maxWait,
            Duration ttl) {
        return new ScraperTrafficLimiter(lockManager, trafficConfig, enabled, true, egressIp, defaultGapMs, maxWait, ttl);
    }

    private static String sourceKey() {
        return "traffic:limit:DataSource:Scraper:Ip:" + EGRESS_IP + ":" + SOURCE_ID;
    }

    private static class FakeDistributedLockManager implements DistributedLockManager {

        private final java.util.Queue<TimeSliceReservation> reservations = new java.util.ArrayDeque<>();
        private final List<TimeSliceReservationRequest> timeSliceRequests = new java.util.ArrayList<>();

        private FakeDistributedLockManager(TimeSliceReservation... reservations) {
            this.reservations.addAll(List.of(reservations));
        }

        @Override
        public DistributedLockProfile profileFor(DistributedLockBusinessType businessType) {
            return DistributedLockProfiles.profileFor(businessType);
        }

        @Override
        public TimeSliceReservation reserveTimeSlice(TimeSliceReservationRequest request) {
            this.timeSliceRequests.add(request);
            TimeSliceReservation reservation = reservations.poll();
            if (reservation == null) {
                return TimeSliceReservation.reserved(
                        request.key(),
                        Duration.ZERO,
                        Instant.parse("2026-06-11T00:00:00Z"),
                        Instant.parse("2026-06-11T00:00:01Z"));
            }
            return reservation;
        }

        @Override
        public TokenBucketReservation reserveTokenBucket(TokenBucketReservationRequest request) {
            throw new UnsupportedOperationException("not used by scraper traffic limiter");
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
