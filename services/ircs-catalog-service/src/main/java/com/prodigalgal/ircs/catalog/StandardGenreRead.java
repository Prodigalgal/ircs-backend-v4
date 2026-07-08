package com.prodigalgal.ircs.catalog;

import java.util.UUID;

public record StandardGenreRead(UUID id, String name, String code) {

    public StandardGenreRead(UUID id, String name) {
        this(id, name, null);
    }
}
