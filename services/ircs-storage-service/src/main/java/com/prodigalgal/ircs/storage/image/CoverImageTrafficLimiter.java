package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.outbound.EgressIdentities;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class CoverImageTrafficLimiter {

    private static final String GLOBAL_KEY_PREFIX = "traffic:limit:Global:ImageDownload:Ip:";
    private static final String DOMAIN_KEY_PREFIX = "traffic:limit:Domain:ImageDownload:Ip:";

    private final DistributedLockManager lockManager;
    private final RuntimeConfigService runtimeConfig;
    private final boolean enabledByDeployment;
    private final String egressIdentityByDeployment;
    private final Duration globalGapByDeployment;
    private final Duration domainGapByDeployment;
    private final Duration maxWaitByDeployment;
    private final Duration ttlByDeployment;

    CoverImageTrafficLimiter(
            DistributedLockManager lockManager,
            ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
            @Value("${app.storage.image.traffic.enabled:true}") boolean enabled,
            @Value("${app.storage.image.traffic.egress-id:${APP_STORAGE_TRAFFIC_EGRESS_ID:${IRCS_TRAFFIC_EGRESS_ID:${HOST_IP:${HOSTNAME:unknown}}}}}") String egressIdentity,
            @Value("${app.storage.image.traffic.global-gap-ms:500}") long globalGapMs,
            @Value("${app.storage.image.traffic.domain-gap-ms:1000}") long domainGapMs,
            @Value("${app.storage.image.traffic.max-wait:PT2M}") Duration maxWait,
            @Value("${app.storage.image.traffic.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.runtimeConfig = runtimeConfigProvider == null ? null : runtimeConfigProvider.getIfAvailable();
        this.enabledByDeployment = enabled;
        this.egressIdentityByDeployment = EgressIdentities.sanitize(egressIdentity);
        this.globalGapByDeployment = positiveOr(globalGapMs, Duration.ofMillis(500));
        this.domainGapByDeployment = positiveOr(domainGapMs, Duration.ofSeconds(1));
        this.maxWaitByDeployment = maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait;
        this.ttlByDeployment = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
    }

    static CoverImageTrafficLimiter noop() {
        return new CoverImageTrafficLimiter(null, null, false, "unknown", 0, 0, Duration.ZERO, Duration.ZERO);
    }

    void acquire(URI uri) {
        if (!enabled() || lockManager == null) {
            return;
        }
        String egressIdentity = egressIdentity();
        reserve(GLOBAL_KEY_PREFIX + egressIdentity, globalGap(), "image download");
        String host = uri == null ? null : uri.getHost();
        if (!StringUtils.hasText(host)) {
            return;
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.DOMAIN_FETCH);
        reserve(
                DOMAIN_KEY_PREFIX + egressIdentity + ":" + EgressIdentities.sanitize(host.toLowerCase(Locale.ROOT)),
                domainGap(),
                host);
    }

    private void reserve(String key, Duration gap, String label) {
        TimeSliceReservation reservation = lockManager.reserveTimeSlice(new TimeSliceReservationRequest(
                key,
                gap,
                maxWait(),
                ttl()));
        if (reservation.rejected()) {
            throw new IllegalStateException("Image download traffic limit saturated: " + label);
        }
        sleepIfNeeded(reservation.waitTime(), label);
    }

    private void sleepIfNeeded(Duration waitTime, String label) {
        if (waitTime == null || !waitTime.isPositive()) {
            return;
        }
        try {
            Thread.sleep(waitTime.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for image download traffic limit: " + label, ex);
        }
    }

    private static Duration positiveOr(long millis, Duration fallback) {
        return millis > 0 ? Duration.ofMillis(millis) : fallback;
    }

    private boolean enabled() {
        return runtimeConfig == null
                ? enabledByDeployment
                : runtimeConfig.booleanValue("app.storage.image.traffic.enabled", enabledByDeployment);
    }

    private String egressIdentity() {
        String value = runtimeConfig == null
                ? egressIdentityByDeployment
                : runtimeConfig.stringValue("app.storage.image.traffic.egress-id", egressIdentityByDeployment);
        return EgressIdentities.resolve(value, egressIdentityByDeployment);
    }

    private Duration globalGap() {
        return gap("app.storage.image.traffic.global-gap-ms", globalGapByDeployment);
    }

    private Duration domainGap() {
        return gap("app.storage.image.traffic.domain-gap-ms", domainGapByDeployment);
    }

    private Duration gap(String key, Duration fallback) {
        long configured = runtimeConfig == null ? fallback.toMillis() : runtimeConfig.longValue(key, fallback.toMillis());
        return configured > 0 ? Duration.ofMillis(configured) : fallback;
    }

    private Duration maxWait() {
        Duration value = runtimeConfig == null
                ? maxWaitByDeployment
                : runtimeConfig.durationValue("app.storage.image.traffic.max-wait", maxWaitByDeployment);
        return value == null || value.isNegative() ? maxWaitByDeployment : value;
    }

    private Duration ttl() {
        Duration value = runtimeConfig == null
                ? ttlByDeployment
                : runtimeConfig.durationValue("app.storage.image.traffic.ttl", ttlByDeployment);
        return value == null || !value.isPositive() ? ttlByDeployment : value;
    }
}
