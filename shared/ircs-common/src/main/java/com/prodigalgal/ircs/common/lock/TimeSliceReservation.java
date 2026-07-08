package com.prodigalgal.ircs.common.lock;

import java.time.Duration;
import java.time.Instant;

public record TimeSliceReservation(
        String key,
        boolean reserved,
        boolean rejected,
        Duration waitTime,
        Instant reservedAt,
        Instant nextAvailableAt) {

    public static TimeSliceReservation reserved(String key, Duration waitTime, Instant reservedAt, Instant nextAvailableAt) {
        return new TimeSliceReservation(key, true, false, waitTime, reservedAt, nextAvailableAt);
    }

    public static TimeSliceReservation rejected(String key, Duration waitTime, Instant checkedAt, Instant nextAvailableAt) {
        return new TimeSliceReservation(key, false, true, waitTime, checkedAt, nextAvailableAt);
    }
}
