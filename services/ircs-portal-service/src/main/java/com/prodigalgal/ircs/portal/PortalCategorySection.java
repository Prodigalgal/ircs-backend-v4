package com.prodigalgal.ircs.portal;

import java.util.List;

public record PortalCategorySection(
        String id,
        String title,
        List<PortalMovieCard> movies) {
}
