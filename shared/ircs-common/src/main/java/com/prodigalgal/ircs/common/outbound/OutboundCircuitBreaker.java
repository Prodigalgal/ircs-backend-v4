package com.prodigalgal.ircs.common.outbound;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutboundCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(OutboundCircuitBreaker.class);

    private final Clock clock;
    private final Map<String, CircuitState> states = new ConcurrentHashMap<>();

    public OutboundCircuitBreaker() {
        this(Clock.systemUTC());
    }

    OutboundCircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    public void beforeCall(String key, OutboundCircuitBreakerConfig config) throws OutboundCircuitOpenException {
        if (!config.enabled()) {
            return;
        }
        state(key).beforeCall(key, config, clock.instant());
    }

    public void recordSuccess(String key, OutboundCircuitBreakerConfig config) {
        if (!config.enabled()) {
            return;
        }
        state(key).recordSuccess(key);
    }

    public void recordFailure(String key, OutboundCircuitBreakerConfig config, String reason) {
        if (!config.enabled()) {
            return;
        }
        state(key).recordFailure(key, config, clock.instant(), reason);
    }

    private CircuitState state(String key) {
        return states.computeIfAbsent(key, ignored -> new CircuitState());
    }

    private static final class CircuitState {

        private State state = State.CLOSED;
        private int consecutiveFailures;
        private Instant openedAt;
        private int halfOpenInFlight;

        synchronized void beforeCall(
                String key,
                OutboundCircuitBreakerConfig config,
                Instant now) throws OutboundCircuitOpenException {
            if (state == State.OPEN && !now.minus(config.openDuration()).isBefore(openedAt)) {
                state = State.HALF_OPEN;
                halfOpenInFlight = 0;
                log.debug("outbound circuit half_open key={}", key);
            }
            if (state == State.OPEN) {
                log.warn("outbound circuit rejected key={} state=open", key);
                throw new OutboundCircuitOpenException("Outbound circuit is open: " + key);
            }
            if (state == State.HALF_OPEN) {
                if (halfOpenInFlight >= config.halfOpenMaxCalls()) {
                    log.warn("outbound circuit rejected key={} state=half_open", key);
                    throw new OutboundCircuitOpenException("Outbound circuit half-open probe limit reached: " + key);
                }
                halfOpenInFlight++;
            }
        }

        synchronized void recordSuccess(String key) {
            if (state != State.CLOSED || consecutiveFailures != 0) {
                log.debug("outbound circuit closed key={}", key);
            }
            state = State.CLOSED;
            consecutiveFailures = 0;
            openedAt = null;
            halfOpenInFlight = 0;
        }

        synchronized void recordFailure(
                String key,
                OutboundCircuitBreakerConfig config,
                Instant now,
                String reason) {
            if (state == State.HALF_OPEN) {
                open(key, now, reason);
                return;
            }
            if (state == State.OPEN) {
                return;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= config.failureThreshold()) {
                open(key, now, reason);
            } else {
                log.debug("outbound circuit failure key={} failures={} reason={}",
                        key, consecutiveFailures, reason);
            }
        }

        private void open(String key, Instant now, String reason) {
            state = State.OPEN;
            openedAt = now;
            halfOpenInFlight = 0;
            log.warn("outbound circuit opened key={} reason={}", key, reason);
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
