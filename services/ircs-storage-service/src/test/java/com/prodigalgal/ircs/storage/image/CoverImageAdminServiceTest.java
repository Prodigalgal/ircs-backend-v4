package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageResponse;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class CoverImageAdminServiceTest {

    private final CoverImageAdminRepository repository = org.mockito.Mockito.mock(CoverImageAdminRepository.class);
    private final ImageSecurityValidator validator = org.mockito.Mockito.mock(ImageSecurityValidator.class);
    private final FileNormalizationService normalizationService = org.mockito.Mockito.mock(FileNormalizationService.class);
    private final LocalObjectStorage localObjectStorage = org.mockito.Mockito.mock(LocalObjectStorage.class);
    private final R2ObjectStorage r2ObjectStorage = org.mockito.Mockito.mock(R2ObjectStorage.class);
    private final StorageCommandPublisher publisher = org.mockito.Mockito.mock(StorageCommandPublisher.class);
    private final StorageWorkPublisher storageWorkPublisher = org.mockito.Mockito.mock(StorageWorkPublisher.class);

    @Test
    void uploadStoresFileAndPersistsMetadata() {
        CoverImageAdminService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", new byte[] {1});
        NormalizedFile normalized = new NormalizedFile(new byte[] {1}, "hash", "image/png", ".png", 1, "covers/hash.png");
        CoverImageResponse response = response(UUID.randomUUID(), CoverImageStorageType.LOCAL, CoverImageStatus.LOCAL_STORED);
        when(normalizationService.normalize(new byte[] {1}, "image/png", "covers")).thenReturn(normalized);
        when(localObjectStorage.exists("covers/hash.png")).thenReturn(false);
        when(repository.createLocal(normalized)).thenReturn(response);

        CoverImageResponse actual = service.manualUpload(file);

        assertEquals(response, actual);
        verify(validator).validateFilename("cover.png");
        verify(localObjectStorage).store(new byte[] {1}, "covers/hash.png", "image/png");
        verify(repository).createLocal(normalized);
        verify(storageWorkPublisher, never()).enqueueCoverR2Sync(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uploadQueuesCoverR2SyncWhenR2IsActive() {
        CoverImageAdminService service = newService();
        UUID imageId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", new byte[] {1});
        NormalizedFile normalized = new NormalizedFile(new byte[] {1}, "hash", "image/png", ".png", 1, "covers/hash.png");
        CoverImageResponse response = response(imageId, CoverImageStorageType.LOCAL, CoverImageStatus.LOCAL_STORED);
        when(normalizationService.normalize(new byte[] {1}, "image/png", "covers")).thenReturn(normalized);
        when(localObjectStorage.exists("covers/hash.png")).thenReturn(true);
        when(repository.createLocal(normalized)).thenReturn(response);
        when(r2ObjectStorage.isActive()).thenReturn(true);

        assertEquals(response, service.manualUpload(file));

        verify(storageWorkPublisher).enqueueCoverR2Sync(imageId, "cover-manual-upload");
    }

    @Test
    void uploadRejectsOversizedFileBeforeNormalization() {
        CoverImageAdminService service = newService(1);
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", new byte[] {1, 2});

        StorageApiException ex = assertThrows(StorageApiException.class, () -> service.manualUpload(file));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("File is too large", ex.getMessage());
        verify(normalizationService, never()).normalize(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteMarksPendingAndPublishesUnlinkCommand() {
        CoverImageAdminService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findRowById(id)).thenReturn(Optional.of(row(id, CoverImageStatus.LOCAL_STORED)));

        service.delete(id);

        verify(repository).markPendingDelete(id);
        verify(publisher).publishImageUnlink(id);
    }

    @Test
    void deleteIgnoresAlreadyPendingImage() {
        CoverImageAdminService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findRowById(id)).thenReturn(Optional.of(row(id, CoverImageStatus.PENDING_DELETE)));

        service.delete(id);

        verify(repository, never()).markPendingDelete(id);
        verify(publisher, never()).publishImageUnlink(id);
    }

    @Test
    void retryRejectsNonFailedState() {
        CoverImageAdminService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findRowById(id)).thenReturn(Optional.of(row(id, CoverImageStatus.LOCAL_STORED)));

        StorageApiException ex = assertThrows(StorageApiException.class, () -> service.retryDownload(id));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        verify(publisher, never()).publishImageDownload(id);
    }

    @Test
    void retryResetsAndPublishesDownloadCommand() {
        CoverImageAdminService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findRowById(id)).thenReturn(Optional.of(row(id, CoverImageStatus.FAILED)));

        service.retryDownload(id);

        verify(repository).resetForDownload(id);
        verify(publisher).publishImageDownload(id);
    }

    @Test
    void createFromUrlQueuesDownloadCommand() {
        CoverImageAdminService service = newService();
        UUID id = UUID.randomUUID();
        when(repository.getOrCreateExternalReference("https://images.example.test/a.jpg"))
                .thenReturn(row(id, CoverImageStatus.UNPROCESSED));
        CoverImageResponse response = response(id, CoverImageStorageType.EXTERNAL, CoverImageStatus.UNPROCESSED);
        when(repository.findResponseById(id)).thenReturn(Optional.of(response));

        assertEquals(response, service.createFromUrl("https://images.example.test/a.jpg"));

        verify(publisher).publishImageDownload(id);
    }

    @Test
    void enqueueDownloadBackfillPublishesCandidateCommands() {
        CoverImageAdminService service = newService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findDownloadCandidates(50)).thenReturn(List.of(first, second));

        assertEquals(2, service.enqueueDownloadBackfill(50));

        verify(publisher).publishImageDownload(first);
        verify(publisher).publishImageDownload(second);
    }

    @Test
    void triggerR2SyncQueuesRuntimeWorkAfterEligibilityCheck() {
        CoverImageAdminService service = newService();
        UUID id = UUID.randomUUID();
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(repository.findRowById(id)).thenReturn(Optional.of(row(id, CoverImageStatus.LOCAL_STORED)));

        service.triggerR2Sync(id);

        verify(storageWorkPublisher).enqueueCoverR2Sync(id, "cover-manual-sync");
    }

    private CoverImageAdminService newService() {
        return newService(10L * 1024 * 1024);
    }

    private CoverImageAdminService newService(long maxUploadBytes) {
        CoverImageAdminService service = new CoverImageAdminService(
                repository,
                validator,
                normalizationService,
                localObjectStorage,
                r2ObjectStorage,
                publisher,
                storageWorkPublisher);
        ReflectionTestUtils.setField(service, "coverPathPrefix", "covers");
        ReflectionTestUtils.setField(service, "maxUploadBytes", maxUploadBytes);
        return service;
    }

    private CoverImageRow row(UUID id, CoverImageStatus status) {
        return new CoverImageRow(
                id,
                CoverImageStorageType.LOCAL,
                status,
                "covers/hash.png",
                "covers/hash.png",
                1L,
                "image/png",
                "hash",
                UUID.randomUUID(),
                "LOCAL_STORAGE",
                0,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private CoverImageResponse response(UUID id, CoverImageStorageType storageType, CoverImageStatus status) {
        return new CoverImageResponse(
                id,
                storageType,
                status,
                "covers/hash.png",
                "covers/hash.png",
                "covers/hash.png",
                1L,
                "image/png",
                "hash",
                "LOCAL_STORAGE",
                0,
                null,
                Instant.now(),
                Instant.now());
    }
}
