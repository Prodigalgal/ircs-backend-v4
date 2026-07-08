package com.prodigalgal.ircs.portal;

import java.util.List;

public record PortalHomeResponse(
        List<PortalMovieCard> spotlight,
        List<PortalMovieCard> trending,
        List<PortalCategorySection> sections) {
}
