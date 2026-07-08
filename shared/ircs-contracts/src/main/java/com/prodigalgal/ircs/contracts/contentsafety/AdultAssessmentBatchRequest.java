package com.prodigalgal.ircs.contracts.contentsafety;

import java.util.List;

public record AdultAssessmentBatchRequest(List<AdultAssessmentItem> items) {

    public AdultAssessmentBatchRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
