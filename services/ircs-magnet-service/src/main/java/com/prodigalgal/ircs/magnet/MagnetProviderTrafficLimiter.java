package com.prodigalgal.ircs.magnet;

import com.prodigalgal.ircs.common.lock.DistributedLockBusinessType;
import com.prodigalgal.ircs.common.lock.DistributedLockManager;
import com.prodigalgal.ircs.common.lock.DistributedLockProfile;
import com.prodigalgal.ircs.common.lock.TimeSliceReservation;
import com.prodigalgal.ircs.common.lock.TimeSliceReservationRequest;
import com.prodigalgal.ircs.common.outbound.EgressIdentities;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class MagnetProviderTrafficLimiter {

    static final String FAILURE_TYPE = "MAGNET_PROVIDER_TRAFFIC_SATURATED";

    private final DistributedLockManager lockManager;
    private final MagnetTrafficConfigValues configValues;
    private final boolean enabledByDeployment;
    private final String egressIdentityByDeployment;
    private final Duration defaultGapByDeployment;
    private final String providerGapByDeployment;
    private final Duration maxWaitByDeployment;
    private final Duration ttlByDeployment;

    MagnetProviderTrafficLimiter(
            DistributedLockManager lockManager,
            MagnetTrafficConfigValues configValues,
            @Value("${app.magnet.traffic.enabled:true}") boolean enabled,
            @Value("${app.magnet.traffic.egress-id:${APP_MAGNET_TRAFFIC_EGRESS_ID:${IRCS_TRAFFIC_EGRESS_ID:${HOST_IP:${HOSTNAME:unknown}}}}}") String egressIdentity,
            @Value("${app.magnet.traffic.default-gap-ms:3000}") long defaultGapMs,
            @Value("${app.magnet.traffic.provider-gap-ms:}") String providerGap,
            @Value("${app.magnet.traffic.max-wait:PT2M}") Duration maxWait,
            @Value("${app.magnet.traffic.ttl:PT10M}") Duration ttl) {
        this.lockManager = lockManager;
        this.configValues = configValues;
        this.enabledByDeployment = enabled;
        this.egressIdentityByDeployment = EgressIdentities.sanitize(egressIdentity);
        this.defaultGapByDeployment = defaultGapMs > 0 ? Duration.ofMillis(defaultGapMs) : Duration.ofSeconds(1);
        this.providerGapByDeployment = providerGap == null ? "" : providerGap;
        this.maxWaitByDeployment = maxWait == null || maxWait.isNegative() ? Duration.ZERO : maxWait;
        this.ttlByDeployment = ttl == null || !ttl.isPositive() ? Duration.ofMinutes(10) : ttl;
    }

    static MagnetProviderTrafficLimiter noop() {
        return new MagnetProviderTrafficLimiter(null, null, false, "unknown", 0, "", Duration.ZERO, Duration.ZERO);
    }

    void acquireProviderSlot(MagnetProviderSummary provider) {
        if (!enabled() || lockManager == null) {
            return;
        }
        String keyPart = keyPart(provider);
        if (!StringUtils.hasText(keyPart)) {
            return;
        }
        DistributedLockProfile profile = lockManager.profileFor(DistributedLockBusinessType.PROVIDER_FETCH);
        TimeSliceReservation reservation = lockManager.reserveTimeSlice(new TimeSliceReservationRequest(
                profile.keyPrefix() + "Magnet:Ip:" + egressIdentity() + ":" + EgressIdentities.sanitize(keyPart),
                gap(provider),
                maxWait(),
                ttl()));
        if (reservation.rejected()) {
            throw new MagnetProviderRunnerException(FAILURE_TYPE, null, null);
        }
        sleepIfNeeded(reservation.waitTime());
    }

    private String keyPart(MagnetProviderSummary provider) {
        if (provider == null) {
            return null;
        }
        if (provider.id() != null) {
            return provider.id().toString();
        }
        if (StringUtils.hasText(provider.code())) {
            return provider.code().trim();
        }
        return provider.providerType();
    }

    private Duration gap(MagnetProviderSummary provider) {
        Duration configured = configuredProviderGap(provider);
        if (configured != null) {
            return configured;
        }
        if (provider != null && provider.minDelayMs() != null && provider.minDelayMs() > 0) {
            return Duration.ofMillis(provider.minDelayMs());
        }
        return defaultGap();
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

    private Duration configuredProviderGap(MagnetProviderSummary provider) {
        return configValues == null
                ? MagnetTrafficConfigValues.configuredProviderGap(provider, providerGapByDeployment)
                : configValues.providerGap(provider, providerGapByDeployment);
    }

    private Duration maxWait() {
        return configValues == null ? maxWaitByDeployment : configValues.limiterMaxWait(maxWaitByDeployment);
    }

    private Duration ttl() {
        return configValues == null ? ttlByDeployment : configValues.ttl(ttlByDeployment);
    }

    private void sleepIfNeeded(Duration waitTime) {
        if (waitTime == null || !waitTime.isPositive()) {
            return;
        }
        try {
            Thread.sleep(waitTime.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MagnetProviderRunnerException(FAILURE_TYPE, null, null);
        }
    }
}
