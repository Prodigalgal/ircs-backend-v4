package com.prodigalgal.ircs.contracts.contentsafety;

import java.util.Map;

public record AdultAssessmentModelResult(
        String name,
        String version,
        boolean available,
        double adultScore,
        String label,
        Map<String, Object> raw) {

    public AdultAssessmentModelResult {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public static AdultAssessmentModelResult unavailable(String name, String version) {
        return new AdultAssessmentModelResult(name, version, false, 0.0d, "UNAVAILABLE", Map.of());
    }
}
