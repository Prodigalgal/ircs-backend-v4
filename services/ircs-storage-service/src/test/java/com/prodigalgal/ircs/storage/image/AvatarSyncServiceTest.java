package com.prodigalgal.ircs.storage.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class AvatarSyncServiceTest {

    @Mock
    private AvatarSyncMemberRepository memberRepository;

    @Mock
    private LocalObjectStorage localObjectStorage;

    @Mock
    private R2ObjectStorage r2ObjectStorage;

    @Mock
    private SystemConfigRepository configRepository;

    @Test
    void skipsWhenR2Inactive() {
        UUID memberId = UUID.randomUUID();
        when(r2ObjectStorage.isActive()).thenReturn(false);

        AvatarSyncService.AvatarSyncResult result = newService().sync(memberId);

        assertFalse(result.synced());
        assertEquals("r2 inactive", result.reason());
        verify(memberRepository, never()).findAvatarUrl(memberId);
    }

    @Test
    void skipsNonLocalAvatarUrl() {
        UUID memberId = UUID.randomUUID();
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(memberRepository.findAvatarUrl(memberId)).thenReturn(Optional.of("https://cdn.example.com/avatars/a.png"));

        AvatarSyncService.AvatarSyncResult result = newService().sync(memberId);

        assertFalse(result.synced());
        assertEquals("avatar not local", result.reason());
        verify(localObjectStorage, never()).retrieve("avatars/a.png");
    }

    @Test
    void uploadsLocalAvatarRewritesR2UrlAndDeletesLocalCopy() {
        UUID memberId = UUID.randomUUID();
        byte[] data = new byte[] {1, 2, 3};
        when(configRepository.findValue("app.storage.public-path")).thenReturn(Optional.of("/assets"));
        when(configRepository.findValue("app.storage.r2.public-domain")).thenReturn(Optional.of("cdn.example.com"));
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(memberRepository.findAvatarUrl(memberId)).thenReturn(Optional.of("/assets/avatars/hash.png"));
        when(localObjectStorage.retrieve("avatars/hash.png")).thenReturn(Optional.of(data));
        when(memberRepository.updateAvatarUrl(
                memberId,
                "/assets/avatars/hash.png",
                "https://cdn.example.com/avatars/hash.png"))
                .thenReturn(1);

        AvatarSyncService.AvatarSyncResult result = newService().sync(memberId);

        assertTrue(result.synced());
        assertEquals("avatars/hash.png", result.storagePath());
        assertEquals("https://cdn.example.com/avatars/hash.png", result.r2Url());
        verify(r2ObjectStorage).store(data, "avatars/hash.png", "image/png");
        verify(localObjectStorage).deleteIfExists("avatars/hash.png");
    }

    @Test
    void doesNotDeleteLocalCopyWhenMemberAvatarChangedDuringSync() {
        UUID memberId = UUID.randomUUID();
        byte[] data = new byte[] {1, 2, 3};
        when(configRepository.findValue("app.storage.public-path")).thenReturn(Optional.empty());
        when(configRepository.findValue("app.storage.r2.public-domain")).thenReturn(Optional.of("cdn.example.com"));
        when(r2ObjectStorage.isActive()).thenReturn(true);
        when(memberRepository.findAvatarUrl(memberId)).thenReturn(Optional.of("/media/avatars/hash.webp"));
        when(localObjectStorage.retrieve("avatars/hash.webp")).thenReturn(Optional.of(data));
        when(memberRepository.updateAvatarUrl(
                memberId,
                "/media/avatars/hash.webp",
                "https://cdn.example.com/avatars/hash.webp"))
                .thenReturn(0);

        AvatarSyncService.AvatarSyncResult result = newService().sync(memberId);

        assertFalse(result.synced());
        assertEquals("avatar changed during sync", result.reason());
        verify(r2ObjectStorage).store(data, "avatars/hash.webp", "image/webp");
        verify(localObjectStorage, never()).deleteIfExists("avatars/hash.webp");
    }

    private AvatarSyncService newService() {
        return new AvatarSyncService(
                memberRepository,
                localObjectStorage,
                r2ObjectStorage,
                new StorageConfigValues(new MockEnvironment(), configRepository));
    }
}
