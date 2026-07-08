package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.storage.image.AvatarStorageDtos.AvatarUploadResponse;
import com.prodigalgal.ircs.storage.image.CoverImageDtos.NormalizedFile;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

class AvatarStorageServiceTest {

    private final ImageSecurityValidator validator = org.mockito.Mockito.mock(ImageSecurityValidator.class);
    private final FileNormalizationService normalizationService = org.mockito.Mockito.mock(FileNormalizationService.class);
    private final LocalObjectStorage localObjectStorage = org.mockito.Mockito.mock(LocalObjectStorage.class);
    private final SystemConfigRepository configRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void storesAvatarAndReturnsMediaUrl() throws Exception {
        when(configRepository.findValue("app.storage.path.prefix.avatar")).thenReturn(Optional.of("profiles"));
        when(configRepository.findValue("app.storage.public-path")).thenReturn(Optional.of("/assets"));
        AvatarStorageService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes());
        NormalizedFile normalized = new NormalizedFile(
                pngBytes(),
                "hash",
                "image/png",
                ".png",
                pngBytes().length,
                "profiles/hash.png");
        when(normalizationService.normalize(any(byte[].class), eq("image/png"), eq("profiles"))).thenReturn(normalized);
        when(localObjectStorage.exists("profiles/hash.png")).thenReturn(false);

        AvatarUploadResponse response = service.store(file);

        assertEquals("/assets/profiles/hash.png", response.url());
        assertEquals("profiles/hash.png", response.storageKey());
        assertEquals("image/png", response.mimeType());
        verify(validator).validateFilename("avatar.png");
        verify(localObjectStorage).store(any(byte[].class), eq("profiles/hash.png"), eq("image/png"));
    }

    @Test
    void skipsStoreWhenAvatarAlreadyExists() throws Exception {
        when(configRepository.findValue("app.storage.path.prefix.avatar")).thenReturn(Optional.empty());
        AvatarStorageService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes());
        NormalizedFile normalized = new NormalizedFile(
                pngBytes(),
                "hash",
                "image/png",
                ".png",
                pngBytes().length,
                "avatars/hash.png");
        when(normalizationService.normalize(any(byte[].class), eq("image/png"), eq("avatars"))).thenReturn(normalized);
        when(localObjectStorage.exists("avatars/hash.png")).thenReturn(true);

        service.store(file);

        verify(localObjectStorage, never()).store(any(byte[].class), eq("avatars/hash.png"), eq("image/png"));
    }

    @Test
    void rejectsOversizedAvatar() {
        AvatarStorageService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[2 * 1024 * 1024 + 1]);

        StorageApiException ex = assertThrows(StorageApiException.class, () -> service.store(file));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        verify(localObjectStorage, never()).store(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void rejectsUnsafeFilename() {
        AvatarStorageService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "../avatar.png", "image/png", pngBytes());
        org.mockito.Mockito.doThrow(new SecurityException("bad filename"))
                .when(validator)
                .validateFilename("../avatar.png");

        StorageApiException ex = assertThrows(StorageApiException.class, () -> service.store(file));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        verify(localObjectStorage, never()).store(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    private AvatarStorageService newService() {
        return new AvatarStorageService(
                validator,
                normalizationService,
                localObjectStorage,
                new StorageConfigValues(new MockEnvironment(), configRepository));
    }

    private byte[] pngBytes() {
        return new byte[] {
                (byte) 0x89,
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
                0x00
        };
    }
}
