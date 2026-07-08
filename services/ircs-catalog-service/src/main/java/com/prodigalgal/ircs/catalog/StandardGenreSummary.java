package com.prodigalgal.ircs.catalog;

import java.util.UUID;

public record StandardGenreSummary(UUID id, String name, String code) {

    public StandardGenreSummary(UUID id, String name) {
        this(id, name, null);
    }
}
