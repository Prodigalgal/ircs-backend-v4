package com.prodigalgal.ircs.search.sync;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SearchSyncMessage;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import com.prodigalgal.ircs.search.document.RawVideoSearchDocument;
import com.prodigalgal.ircs.search.index.SearchIndexService;
import com.prodigalgal.ircs.search.repository.SearchDocumentJdbcRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JdbcSearchSyncProcessorTest {

    @Mock
    private SearchDocumentJdbcRepository documentRepository;

    @Mock
    private SearchIndexService searchIndexService;

    @Test
    void indexesRawVideoWhenDocumentExists() {
        UUID id = UUID.randomUUID();
        RawVideoSearchDocument document = new RawVideoSearchDocument();
        document.setId(id);
        when(documentRepository.findRawVideo(id)).thenReturn(Optional.of(document));

        JdbcSearchSyncProcessor processor = new JdbcSearchSyncProcessor(documentRepository, searchIndexService);
        processor.process(new SearchSyncMessage(id, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX));

        verify(searchIndexService).saveRaw(document);
        verify(searchIndexService, never()).delete(id, SearchEntityType.RAW_VIDEO);
    }

    @Test
    void deletesRawVideoWhenIndexMessageReferencesMissingEntity() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findRawVideo(id)).thenReturn(Optional.empty());

        JdbcSearchSyncProcessor processor = new JdbcSearchSyncProcessor(documentRepository, searchIndexService);
        processor.process(new SearchSyncMessage(id, SearchEntityType.RAW_VIDEO, SyncOperation.INDEX));

        verify(searchIndexService).delete(id, SearchEntityType.RAW_VIDEO);
    }

    @Test
    void deleteOperationDoesNotReadDatabase() {
        UUID id = UUID.randomUUID();

        JdbcSearchSyncProcessor processor = new JdbcSearchSyncProcessor(documentRepository, searchIndexService);
        processor.process(new SearchSyncMessage(id, SearchEntityType.UNIFIED_VIDEO, SyncOperation.DELETE));

        verify(documentRepository, never()).findUnifiedVideo(id);
        verify(searchIndexService).delete(id, SearchEntityType.UNIFIED_VIDEO);
    }
}
