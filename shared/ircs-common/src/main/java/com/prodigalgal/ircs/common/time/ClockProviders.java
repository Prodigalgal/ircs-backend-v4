package com.prodigalgal.ircs.common.time;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;

public final class ClockProviders {

    private ClockProviders() {
    }

    public static Clock uniqueOrSystemUtc(ObjectProvider<Clock> provider) {
        if (provider == null) {
            return Clock.systemUTC();
        }
        Clock clock = provider.getIfUnique();
        return clock == null ? Clock.systemUTC() : clock;
    }
}
