package com.prodigalgal.ircs.contracts.contentsafety;

public record AdultAssessmentSignalDto(
        String source,
        String field,
        String matchedValue,
        int score,
        String reason) {
}
