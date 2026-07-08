package com.prodigalgal.ircs.metadata.dispatch.dto;

import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.util.List;
import java.util.UUID;

public record MetadataDispatchResult(UUID videoId, boolean videoFound, String status, List<ProviderType> providers) {
}
