package com.prodigalgal.ircs.contracts.contentsafety;

public record AdultAssessmentSourceEvidence(
        String dataSourceName,
        boolean dataSourceAdultRestricted,
        String sourceCategoryCode,
        String sourceCategoryName,
        String sourceDomain,
        String rawMetadata) {
}
