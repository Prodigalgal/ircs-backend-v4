package com.prodigalgal.ircs.identity.application;









import com.prodigalgal.ircs.identity.dto.PageBounds;
import com.prodigalgal.ircs.identity.repository.MemberAdminRepository;
import com.prodigalgal.ircs.identity.dto.PageResponse;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.identity.dto.HistoryRecordResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberAdminResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberAdminUpdateRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class MemberAdminServiceTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberAdminRepository memberAdminRepository = mock(MemberAdminRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final MemberStatusCacheService statusCacheService = mock(MemberStatusCacheService.class);
    private final MemberAdminService service = new MemberAdminService(
            memberRepository,
            memberAdminRepository,
            passwordEncoder,
            statusCacheService,
            new MemberResponseMapper());

    @Test
    void findAllKeepsV1PageShapeAndMemberFields() {
        PageBounds bounds = new PageBounds(1, 5);
        MemberRecord member = member(UUID.randomUUID(), MemberStatus.ACTIVE);
        when(memberAdminRepository.findMembers(" codex ", MemberStatus.ACTIVE, true, 10, 100, bounds, "email,asc"))
                .thenReturn(PageResponse.of(List.of(member), 9, bounds));

        PageResponse<MemberAdminResponse> response =
                service.findAll(" codex ", MemberStatus.ACTIVE, true, 10, 100, 1, 5, "email,asc");

        assertEquals(9, response.totalElements());
        assertEquals(2, response.totalPages());
        assertEquals(member.id(), response.content().get(0).id());
        assertEquals(member.email(), response.content().get(0).email());
        assertEquals(member.level(), response.content().get(0).level());
        verify(memberAdminRepository).findMembers(" codex ", MemberStatus.ACTIVE, true, 10, 100, bounds, "email,asc");
    }

    @Test
    void updateMemberPersistsV1AdminFieldsAndRefreshesStatusCache() {
        UUID memberId = UUID.randomUUID();
        MemberRecord current = member(memberId, MemberStatus.ACTIVE);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(current));
        when(memberAdminRepository.existsByEmailExcludingId("next@example.com", memberId)).thenReturn(false);
        when(passwordEncoder.encode("Next123")).thenReturn("hash-next");

        service.updateMember(memberId, new MemberAdminUpdateRequest(
                "NEXT@example.com",
                "Next Nick",
                "https://img.example/avatar.png",
                "Next123",
                "ADMIN",
                MemberStatus.BANNED,
                true,
                300,
                80,
                7));

        ArgumentCaptor<MemberRecord> memberCaptor = ArgumentCaptor.forClass(MemberRecord.class);
        verify(memberRepository).update(memberCaptor.capture());
        MemberRecord saved = memberCaptor.getValue();
        assertEquals("next@example.com", saved.email());
        assertEquals("hash-next", saved.passwordHash());
        assertEquals("Next Nick", saved.nickname());
        assertEquals("ADMIN", saved.role());
        assertEquals(MemberStatus.BANNED, saved.status());
        assertTrue(saved.adultContentAllowed());
        assertEquals(300, saved.experience());
        assertEquals(80, saved.points());
        assertEquals(7, saved.checkInStreak());
        verify(statusCacheService).updateStatus(memberId, MemberStatus.BANNED);
    }

    @Test
    void updateMemberRejectsDuplicateEmail() {
        UUID memberId = UUID.randomUUID();
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, MemberStatus.ACTIVE)));
        when(memberAdminRepository.existsByEmailExcludingId("other@example.com", memberId)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.updateMember(memberId,
                new MemberAdminUpdateRequest("other@example.com", null, null, null, null, null, null, null, null, null)));

        assertEquals("email.exists", ex.errorKey());
        verify(memberRepository, never()).update(any(MemberRecord.class));
    }

    @Test
    void updateStatusNoopsWhenStatusIsUnchanged() {
        UUID memberId = UUID.randomUUID();
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, MemberStatus.ACTIVE)));

        MemberAdminResponse response = service.updateStatus(memberId, MemberStatus.ACTIVE);

        assertEquals(MemberStatus.ACTIVE, response.status());
        verify(memberRepository, never()).update(any(MemberRecord.class));
        verify(statusCacheService, never()).updateStatus(eq(memberId), any(MemberStatus.class));
    }

    @Test
    void deleteClearsV1MemberRelationsBeforeMemberAndEvictsCache() {
        UUID memberId = UUID.randomUUID();
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, MemberStatus.ACTIVE)));

        service.delete(memberId);

        verify(memberAdminRepository).deleteMemberRelations(memberId);
        verify(memberAdminRepository).deleteMember(memberId);
        verify(statusCacheService).evict(memberId);
    }

    @Test
    void favoritesAndHistoryRequireMemberAndReturnPages() {
        UUID memberId = UUID.randomUUID();
        PageBounds bounds = new PageBounds(0, 20);
        HistoryRecordResponse record = new HistoryRecordResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "片名",
                "https://img.example/cover.jpg",
                new BigDecimal("8.7"),
                null,
                null,
                "第 1 集",
                12,
                120,
                Instant.parse("2026-06-06T00:00:00Z"),
                true);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member(memberId, MemberStatus.ACTIVE)));
        when(memberAdminRepository.findFavorites(memberId, bounds)).thenReturn(PageResponse.of(List.of(record), 1, bounds));
        when(memberAdminRepository.findHistory(memberId, bounds)).thenReturn(PageResponse.of(List.of(record), 1, bounds));

        assertEquals("片名", service.favorites(memberId, 0, 20).content().get(0).title());
        assertEquals("第 1 集", service.history(memberId, 0, 20).content().get(0).episodeName());
    }

    private MemberRecord member(UUID id, MemberStatus status) {
        Instant now = Instant.parse("2026-06-06T00:00:00Z");
        return new MemberRecord(
                id,
                now,
                now,
                0L,
                "codex@example.com",
                "hash",
                "Codex",
                "https://img.example/avatar.png",
                "MEMBER",
                status,
                false,
                400,
                20,
                null,
                2);
    }
}
