package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.storage.image.CoverImageDtos.CoverImageRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CoverImageR2SyncServiceTest {

    @Mock
    private CoverImageAdminRepository repository;

    @Mock
    private LocalObjectStorage localObjectStorage;

    @Mock
    private R2ObjectStorage r2ObjectStorage;

    @Test
    void skipsWhenR2Inactive() {
        UUID imageId = UUID.randomUUID();
        when(r2ObjectStorage.isActive()).thenReturn(false);

        CoverImageR2SyncService.CoverImageR2SyncResult result = newService().sync(imageId);

        assertFalse(result.synced());
        assertEquals("r2 inactive", result.reason());
        verify(repository, never()).findRowById(imageId);
    }

    @Test
    void uploadsLocalCoverAndFinalizesR2State() {
        UUID imageId = UUID.randomUUID();
        byte[] data = new byte[] {1, 2, 3};
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row(imageId)));
        when(localObjectStorage.retrieve("covers/a.png")).thenReturn(Optional.of(data));

        CoverImageR2SyncService.CoverImageR2SyncResult result = newService().sync(imageId);

        assertTrue(result.synced());
        assertEquals("covers/a.png", result.storagePath());
        verify(repository).markUploading(imageId);
        verify(r2ObjectStorage).store(data, "covers/a.png", "image/png");
        verify(repository).finalizeUpload(imageId, "covers/a.png");
        verify(localObjectStorage, never()).deleteIfExists("covers/a.png");
    }

    @Test
    void uploadsAlreadyClaimedUploadingCoverWithoutMarkingUploadingAgain() {
        UUID imageId = UUID.randomUUID();
        byte[] data = new byte[] {1, 2, 3};
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row(imageId, CoverImageStatus.UPLOADING)));
        when(localObjectStorage.retrieve("covers/a.png")).thenReturn(Optional.of(data));

        CoverImageR2SyncService.CoverImageR2SyncResult result = newService().sync(imageId);

        assertTrue(result.synced());
        verify(repository, never()).markUploading(imageId);
        verify(repository).finalizeUpload(imageId, "covers/a.png");
    }

    @Test
    void deletesTemporaryLocalCoverAfterR2SyncWhenImageDownloadIsDisabled() {
        UUID imageId = UUID.randomUUID();
        byte[] data = new byte[] {1, 2, 3};
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row(imageId)));
        when(localObjectStorage.retrieve("covers/a.png")).thenReturn(Optional.of(data));

        CoverImageR2SyncService.CoverImageR2SyncResult result = newService(false).sync(imageId);

        assertTrue(result.synced());
        verify(r2ObjectStorage).store(data, "covers/a.png", "image/png");
        verify(repository).finalizeUpload(imageId, "covers/a.png");
        verify(localObjectStorage).deleteIfExists("covers/a.png");
    }

    @Test
    void recordsFailureWhenLocalCoverFileIsMissing() {
        UUID imageId = UUID.randomUUID();
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row(imageId)));
        when(localObjectStorage.retrieve("covers/a.png")).thenReturn(Optional.empty());

        CoverImageR2SyncService.CoverImageR2SyncResult result = newService().sync(imageId);

        assertFalse(result.synced());
        assertTrue(result.failed());
        assertEquals("local file missing", result.reason());
        verify(repository).markUploading(imageId);
        verify(repository).markFailed(imageId, "Local file missing for R2 upload", 3);
        verify(r2ObjectStorage, never()).store(new byte[] {1}, "covers/a.png", "image/png");
    }

    @Test
    void recordsFailureWhenR2UploadFails() {
        UUID imageId = UUID.randomUUID();
        byte[] data = new byte[] {1, 2, 3};
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(repository.findRowById(imageId)).thenReturn(Optional.of(row(imageId)));
        when(localObjectStorage.retrieve("covers/a.png")).thenReturn(Optional.of(data));
        doThrow(new IllegalStateException("boom")).when(r2ObjectStorage).store(data, "covers/a.png", "image/png");

        CoverImageR2SyncService.CoverImageR2SyncResult result = newService().sync(imageId);

        assertFalse(result.synced());
        assertTrue(result.failed());
        assertEquals("r2 upload failed", result.reason());
        verify(repository).markFailed(imageId, "R2 upload failed: boom", 3);
    }

    private CoverImageR2SyncService newService() {
        return newService(true);
    }

    private CoverImageR2SyncService newService(boolean imageDownloadEnabled) {
        CoverImageR2SyncService service = new CoverImageR2SyncService(repository, localObjectStorage, r2ObjectStorage);
        ReflectionTestUtils.setField(service, "maxRetries", 3);
        ReflectionTestUtils.setField(service, "imageDownloadEnabled", imageDownloadEnabled);
        return service;
    }

    private CoverImageRow row(UUID id) {
        return row(id, CoverImageStatus.LOCAL_STORED);
    }

    private CoverImageRow row(UUID id, CoverImageStatus status) {
        Instant now = Instant.now();
        return new CoverImageRow(
                id,
                CoverImageStorageType.LOCAL,
                status,
                "covers/a.png",
                "covers/a.png",
                3L,
                "image/png",
                "hash",
                UUID.randomUUID(),
                "LOCAL_STORAGE",
                0,
                null,
                now,
                now,
                now);
    }
}
