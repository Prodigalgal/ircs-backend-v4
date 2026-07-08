package com.prodigalgal.ircs.portal;

import java.time.Instant;
import java.util.UUID;

public record PortalSitemapMovie(
        UUID id,
        String title,
        String posterUrl,
        Instant lastModified) {
}
