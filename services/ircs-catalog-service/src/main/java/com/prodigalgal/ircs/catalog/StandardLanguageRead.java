package com.prodigalgal.ircs.catalog;

import java.util.UUID;

public record StandardLanguageRead(
        UUID id,
        String name,
        String code,
        String englishName,
        String nativeName) {}
