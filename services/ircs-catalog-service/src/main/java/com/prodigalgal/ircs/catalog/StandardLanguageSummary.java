package com.prodigalgal.ircs.catalog;

import java.util.UUID;

public record StandardLanguageSummary(
        UUID id,
        String name,
        String code,
        String englishName,
        String nativeName) {}
