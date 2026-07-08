package com.prodigalgal.ircs.storage.image;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoverImagePhysicalDeletionServiceTest {

    @Mock
    private CoverImageDeletionRepository repository;

    @Mock
    private LocalObjectStorage localObjectStorage;

    @Mock
    private R2ObjectStorage r2ObjectStorage;

    @Test
    void ignoresMissingImage() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        newService().delete(id);

        verify(repository, never()).deleteById(id);
    }

    @Test
    void deletesMetadataOnlyWhenExternalImageHasNoStoragePath() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id))
                .thenReturn(Optional.of(new CoverImageRecord(id, CoverImageStorageType.EXTERNAL, null)));

        newService().delete(id);

        verify(localObjectStorage, never()).deleteIfExists(null);
        verify(r2ObjectStorage, never()).delete(null);
        verify(repository).deleteById(id);
    }

    @Test
    void deletesLocalFileBeforeMetadataForLocalImage() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id))
                .thenReturn(Optional.of(new CoverImageRecord(id, CoverImageStorageType.LOCAL, "covers/a.webp")));

        newService().delete(id);

        verify(localObjectStorage).deleteIfExists("covers/a.webp");
        verify(r2ObjectStorage, never()).delete("covers/a.webp");
        verify(repository).deleteById(id);
    }

    @Test
    void deletesR2AndLocalCopyWhenR2ImageHasLocalCopy() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id))
                .thenReturn(Optional.of(new CoverImageRecord(id, CoverImageStorageType.R2, "covers/a.webp")));
        when(localObjectStorage.exists("covers/a.webp")).thenReturn(true);

        newService().delete(id);

        verify(localObjectStorage).deleteIfExists("covers/a.webp");
        verify(r2ObjectStorage).delete("covers/a.webp");
        verify(repository).deleteById(id);
    }

    private CoverImagePhysicalDeletionService newService() {
        return new CoverImagePhysicalDeletionService(repository, localObjectStorage, r2ObjectStorage);
    }
}
