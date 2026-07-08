package com.prodigalgal.ircs.common.adult;

public record AdultAssessmentSignal(
        String source,
        String field,
        String matchedValue,
        int score,
        String reason) {
}
