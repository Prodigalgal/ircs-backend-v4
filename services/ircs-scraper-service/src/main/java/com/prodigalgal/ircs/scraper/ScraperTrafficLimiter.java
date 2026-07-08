package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.outbound.EgressIdentities;
import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import com.prodigalgal.ircs.scraper.ScraperDtos.ManualScrapeConfigRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ScraperTrafficLimiter {

    private final DistributedLockManager lockManager;
    private final ScraperTrafficConfigValues configValues;
    private final boolean enabledByDeployment;
    private final String egressIdentityByDeployment;
    private final Duration defaultGapByDeployment;
    private final Duration maxWaitByDeployment;
    private final Duration ttlByDeployment;
    private final boolean sourceEnabledByDeployment;

    ScraperTrafficLimiter(
            DistributedLockManager lockManager,
            ScraperTrafficConfigValues configValues,
            @Value("${app.scraper.traffic.enabled:true}") boolean enabled,
            @Value("${app.scraper.traffic.source-enabled:true}") boolean sourceEnabledByDeployment,
            @Value("${app.scraper.traffic.egress-id:${IRCS_SCRAPER_TRAFFIC_EGRESS_IP:${KUBERNETES_NODE_IP:${HOST_IP:${HOSTNAME:unknown}}}}}") String egressIdentity,
            @Value("${app.scraper.traffic.default-gap-ms:1000}") long defaultGapMs,
            @Value("${app.scraper.traffic.max-wait:PT2M}") Duration maxWait,
            @Value("${app.scraper.traffic.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.configValues = configValues;
        this.enabledByDeployment = enabled;
        this.egressIdentityByDeployment = EgressIdentities.sanitize(egressIdentity);
        this.defaultGapByDeployment = positiveOr(defaultGapMs, Duration.ofSeconds(1));
        this.maxWaitByDeployment = maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait;
        this.ttlByDeployment = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
        this.sourceEnabledByDeployment = sourceEnabledByDeployment;
    }

    static ScraperTrafficLimiter noop() {
        return new ScraperTrafficLimiter(null, null, false, false, "unknown", 0, Duration.ZERO, Duration.ZERO);
    }

    void acquireDataSourceSlot(DataSourceRecord source, ManualScrapeConfigRequest config) {
        if (!enabled() || lockManager == null) {
            return;
        }
        if (source == null || source.id() == null) {
            return;
        }
        if (!sourceEnabled()) {
            return;
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.DATA_SOURCE_SCRAPE);
        reserve(key(profile, source.id()), sourceGap(config), source.name());
    }

    private void reserve(String key, Duration gap, String label) {
        TimeSliceReservation reservation = lockManager.reserveTimeSlice(new TimeSliceReservationRequest(
                key,
                gap,
                effectiveMaxWait(),
                ttl()));
        if (reservation.rejected()) {
            throw new IllegalStateException("Traffic limit saturated: " + label
                    + ", wait=" + reservation.waitTime().toMillis() + "ms");
        }
        sleepIfNeeded(reservation.waitTime(), label);
    }

    private String key(DistributedLockProfile profile, UUID sourceId) {
        return profile.keyPrefix() + "Scraper:Ip:" + egressIdentity() + ":" + sourceId;
    }

    private Duration sourceGap(ManualScrapeConfigRequest config) {
        Duration requestedGap = defaultGap();
        if (config != null && config.fixedDelayMs() != null && config.fixedDelayMs() > 0) {
            requestedGap = Duration.ofMillis(config.fixedDelayMs());
        }
        Duration floor = configValues == null ? Duration.ZERO : configValues.safetyFloor();
        return requestedGap.compareTo(floor) >= 0 ? requestedGap : floor;
    }

    private Duration effectiveMaxWait() {
        return configValues == null ? maxWaitByDeployment : configValues.maxWait(maxWait());
    }

    private boolean sourceEnabled() {
        return configValues == null
                ? sourceEnabledByDeployment
                : configValues.sourceEnabled(sourceEnabledByDeployment);
    }

    private boolean enabled() {
        return configValues == null ? enabledByDeployment : configValues.enabled(enabledByDeployment);
    }

    private String egressIdentity() {
        String value = configValues == null
                ? egressIdentityByDeployment
                : configValues.egressIdentity(egressIdentityByDeployment);
        return EgressIdentities.resolve(value, egressIdentityByDeployment);
    }

    private Duration defaultGap() {
        return configValues == null ? defaultGapByDeployment : configValues.defaultGap(defaultGapByDeployment);
    }

    private Duration maxWait() {
        return configValues == null ? maxWaitByDeployment : configValues.limiterMaxWait(maxWaitByDeployment);
    }

    private Duration ttl() {
        return configValues == null ? ttlByDeployment : configValues.ttl(ttlByDeployment);
    }

    private void sleepIfNeeded(Duration waitTime, String label) {
        if (waitTime == null || !waitTime.isPositive()) {
            return;
        }
        try {
            Thread.sleep(waitTime.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for traffic limit: " + label, ex);
        }
    }

    private static Duration positiveOr(long millis, Duration fallback) {
        return millis > 0 ? Duration.ofMillis(millis) : fallback;
    }

}
