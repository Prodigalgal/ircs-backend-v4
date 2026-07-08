package com.prodigalgal.ircs.contracts.contentsafety;

import java.util.List;

public record AdultAssessmentBatchResponse(
        String engineVersion,
        List<AdultAssessmentResult> items) {

    public AdultAssessmentBatchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
