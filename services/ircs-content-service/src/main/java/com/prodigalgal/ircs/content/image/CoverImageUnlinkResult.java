package com.prodigalgal.ircs.content.image;

import java.util.UUID;

public record CoverImageUnlinkResult(UUID imageId, int rawVideoCount, int unifiedVideoCount) {
}
