package com.prodigalgal.ircs.common.adult;

import java.util.List;

public record AdultAssessment(
        String ruleVersion,
        AdultAssessmentLevel level,
        boolean adultRestricted,
        int confidence,
        List<AdultAssessmentSignal> signals) {

    public AdultAssessment {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public static AdultAssessment safe(String ruleVersion) {
        return new AdultAssessment(ruleVersion, AdultAssessmentLevel.SAFE, false, 0, List.of());
    }
}
