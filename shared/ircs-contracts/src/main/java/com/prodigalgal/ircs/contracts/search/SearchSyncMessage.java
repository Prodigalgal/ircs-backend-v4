package com.prodigalgal.ircs.contracts.search;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchSyncMessage implements Serializable {
    private UUID entityId;
    private SearchEntityType entityType;
    private SyncOperation operation;
}
