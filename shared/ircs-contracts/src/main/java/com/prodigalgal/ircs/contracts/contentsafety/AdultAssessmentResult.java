package com.prodigalgal.ircs.contracts.contentsafety;

import java.util.List;
import java.util.UUID;

public record AdultAssessmentResult(
        UUID id,
        String ruleVersion,
        String level,
        boolean adultRestricted,
        int confidence,
        List<AdultAssessmentSignalDto> signals,
        AdultAssessmentModelResult model) {

    public AdultAssessmentResult {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}
