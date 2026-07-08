package com.prodigalgal.ircs.identity.application;








import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.messaging.AvatarSyncPublisher;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient.StoredAvatar;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AvatarUploadResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.CheckInResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class MemberProfileServiceTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final IdentityConfigService configService = mock(IdentityConfigService.class);
    private final AvatarStorageClient avatarStorageClient = mock(AvatarStorageClient.class);
    private final AvatarSyncPublisher avatarSyncPublisher = mock(AvatarSyncPublisher.class);
    private final MemberProfileService profileService = new MemberProfileService(
            memberRepository,
            new BCryptPasswordEncoder(),
            configService,
            new MemberResponseMapper(),
            avatarStorageClient,
            avatarSyncPublisher);

    @Test
    void uploadAvatarUpdatesMemberAvatarUrl() {
        UUID memberId = UUID.randomUUID();
        MemberRecord member = member(memberId, MemberStatus.ACTIVE);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes());
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(avatarStorageClient.store(any())).thenReturn(storedAvatar("/media/avatars/stored.png"));

        AvatarUploadResponse response = profileService.uploadAvatar(memberId, file);

        assertTrue(response.url().startsWith("/media/avatars/"));
        assertTrue(response.url().endsWith(".png"));
        verify(avatarStorageClient).store(any());
        ArgumentCaptor<MemberRecord> captor = ArgumentCaptor.forClass(MemberRecord.class);
        verify(memberRepository).update(captor.capture());
        assertEquals(response.url(), captor.getValue().avatarUrl());
        assertEquals(memberId, captor.getValue().id());
        verify(avatarSyncPublisher).publish(memberId);
    }

    @Test
    void uploadAvatarRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        ApiException ex = assertThrows(ApiException.class, () -> profileService.uploadAvatar(UUID.randomUUID(), file));

        assertEquals("file.empty", ex.errorKey());
        verifyNoInteractions(memberRepository);
        verifyNoInteractions(avatarStorageClient);
    }

    @Test
    void uploadAvatarRejectsOversizedFile() {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", oversized);

        ApiException ex = assertThrows(ApiException.class, () -> profileService.uploadAvatar(UUID.randomUUID(), file));

        assertEquals("file.size.limit", ex.errorKey());
        verifyNoInteractions(memberRepository);
        verifyNoInteractions(avatarStorageClient);
    }

    @Test
    void uploadAvatarRejectsUnsupportedContentType() {
        UUID memberId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.txt", "text/plain", "not image".getBytes());
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, MemberStatus.ACTIVE)));

        ApiException ex = assertThrows(ApiException.class, () -> profileService.uploadAvatar(memberId, file));

        assertEquals("content.type.invalid", ex.errorKey());
        verifyNoInteractions(avatarStorageClient);
        verify(memberRepository, never()).update(any(MemberRecord.class));
        verifyNoInteractions(avatarSyncPublisher);
    }

    @Test
    void uploadAvatarDoesNotUpdateMemberWhenStorageSyncFails() {
        UUID memberId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", pngBytes());
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, MemberStatus.ACTIVE)));
        when(avatarStorageClient.store(any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "storage-service avatar upload failed",
                        "avatar",
                        "storage.sync.failed"));

        ApiException ex = assertThrows(ApiException.class, () -> profileService.uploadAvatar(memberId, file));

        assertEquals("storage.sync.failed", ex.errorKey());
        verify(memberRepository, never()).update(any(MemberRecord.class));
        verifyNoInteractions(avatarSyncPublisher);
    }

    @Test
    void checkInKeepsV1StreakAndPointRules() {
        UUID memberId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        MemberRecord member = new MemberRecord(
                memberId,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"),
                0L,
                "codex@example.com",
                "$2a$10$unused",
                "Codex",
                "https://i.pravatar.cc/300?u=codex@example.com",
                "MEMBER",
                MemberStatus.ACTIVE,
                false,
                20,
                10,
                today.minusDays(1),
                1);
        when(configService.value(IdentityConfigKey.MEMBER_REGISTER_TIMEZONE)).thenReturn("Asia/Shanghai");
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));

        CheckInResult result = profileService.checkIn(memberId);

        assertEquals(15, result.earnedPoints());
        assertEquals(25, result.currentPoints());
        assertEquals(2, result.checkInStreak());
        assertEquals(40, result.experience());
        verify(memberRepository).update(any(MemberRecord.class));
    }

    private MemberRecord member(UUID id, MemberStatus status) {
        Instant now = Instant.parse("2026-06-04T00:00:00Z");
        return new MemberRecord(
                id,
                now,
                now,
                0L,
                "codex@example.com",
                "$2a$10$unused",
                "Codex",
                "https://i.pravatar.cc/300?u=codex@example.com",
                "MEMBER",
                status,
                false,
                20,
                10,
                null,
                0);
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

    private StoredAvatar storedAvatar(String url) {
        return new StoredAvatar(url, "avatars/stored.png", "image/png", 9L, "stored");
    }
}
