package com.prodigalgal.ircs.contracts.contentsafety;

import java.util.List;
import java.util.UUID;

public record AdultAssessmentItem(
        UUID id,
        String title,
        String aliasTitle,
        String description,
        String remarks,
        String subtitle,
        String categoryCode,
        String categoryName,
        List<String> actorNames,
        List<String> directorNames,
        List<String> genreCodes,
        List<AdultAssessmentSourceEvidence> sources) {

    public AdultAssessmentItem {
        actorNames = actorNames == null ? List.of() : List.copyOf(actorNames);
        directorNames = directorNames == null ? List.of() : List.copyOf(directorNames);
        genreCodes = genreCodes == null ? List.of() : List.copyOf(genreCodes);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
