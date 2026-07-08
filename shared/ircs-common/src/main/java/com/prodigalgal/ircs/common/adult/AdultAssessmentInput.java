package com.prodigalgal.ircs.common.adult;

import java.util.Collection;
import java.util.List;

public record AdultAssessmentInput(
        String title,
        String aliasTitle,
        String description,
        String remarks,
        String subtitle,
        String categoryCode,
        String categoryName,
        Collection<String> actorNames,
        Collection<String> directorNames,
        Collection<String> genreCodes,
        Collection<SourceEvidence> sources) {

    public AdultAssessmentInput {
        actorNames = copy(actorNames);
        directorNames = copy(directorNames);
        genreCodes = copy(genreCodes);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    private static List<String> copy(Collection<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record SourceEvidence(
            String dataSourceName,
            boolean dataSourceAdultRestricted,
            String sourceCategoryCode,
            String sourceCategoryName,
            String sourceDomain,
            String rawMetadata) {
    }
}
