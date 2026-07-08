package com.prodigalgal.ircs.identity.application;









import com.prodigalgal.ircs.identity.dto.PageBounds;
import com.prodigalgal.ircs.identity.repository.MemberAdminRepository;
import com.prodigalgal.ircs.identity.dto.PageResponse;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.identity.dto.HistoryRecordResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberAdminResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberAdminUpdateRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MemberAdminService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository memberRepository;
    private final MemberAdminRepository memberAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final MemberStatusCacheService statusCacheService;
    private final MemberResponseMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<MemberAdminResponse> findAll(
            String keyword,
            MemberStatus status,
            Boolean adultContentAllowed,
            Integer minPoints,
            Integer maxPoints,
            int page,
            int size,
            String sort) {
        PageBounds bounds = PageBounds.of(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        PageResponse<MemberRecord> records = memberAdminRepository.findMembers(
                keyword,
                status,
                adultContentAllowed,
                minPoints,
                maxPoints,
                bounds,
                sort);
        return PageResponse.of(records.content().stream().map(mapper::toAdmin).toList(), records.totalElements(), bounds);
    }

    @Transactional(readOnly = true)
    public PageResponse<HistoryRecordResponse> favorites(UUID memberId, int page, int size) {
        requireMember(memberId);
        return memberAdminRepository.findFavorites(memberId, PageBounds.of(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
    }

    @Transactional(readOnly = true)
    public PageResponse<HistoryRecordResponse> history(UUID memberId, int page, int size) {
        requireMember(memberId);
        return memberAdminRepository.findHistory(memberId, PageBounds.of(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
    }

    @Transactional
    public MemberAdminResponse updateMember(UUID memberId, MemberAdminUpdateRequest request) {
        MemberRecord member = requireMember(memberId);
        String nextEmail = member.email();
        if (request != null && StringUtils.hasText(request.email())) {
            nextEmail = normalizeEmail(request.email());
            if (!member.email().equalsIgnoreCase(nextEmail)
                    && memberAdminRepository.existsByEmailExcludingId(nextEmail, memberId)) {
                throw ApiException.badRequest("Email already exists", "member", "email.exists");
            }
        }

        String nextPasswordHash = member.passwordHash();
        if (request != null && StringUtils.hasText(request.password())) {
            nextPasswordHash = passwordEncoder.encode(request.password());
        }

        MemberStatus nextStatus = request == null || request.status() == null ? member.status() : request.status();
        MemberRecord updated = member.withAdminFields(
                nextEmail,
                nextPasswordHash,
                textOrCurrent(request == null ? null : request.nickname(), member.nickname()),
                textOrCurrent(request == null ? null : request.avatarUrl(), member.avatarUrl()),
                textOrCurrent(request == null ? null : request.role(), member.role()),
                nextStatus,
                request == null || request.experience() == null ? member.experience() : request.experience(),
                request == null || request.points() == null ? member.points() : request.points(),
                request == null || request.checkInStreak() == null ? member.checkInStreak() : request.checkInStreak());
        if (request != null && request.adultContentAllowed() != null) {
            updated = updated.withAdultContentAllowed(request.adultContentAllowed());
        }
        memberRepository.update(updated);
        if (member.status() != updated.status()) {
            statusCacheService.updateStatus(memberId, updated.status());
        }
        return mapper.toAdmin(updated);
    }

    @Transactional
    public MemberAdminResponse updateStatus(UUID memberId, MemberStatus status) {
        if (status == null) {
            throw ApiException.badRequest("状态不能为空", "member", "status.required");
        }
        MemberRecord member = requireMember(memberId);
        if (member.status() == status) {
            return mapper.toAdmin(member);
        }
        MemberRecord updated = member.withStatus(status);
        memberRepository.update(updated);
        statusCacheService.updateStatus(memberId, status);
        return mapper.toAdmin(updated);
    }

    @Transactional
    public void delete(UUID memberId) {
        if (memberRepository.findById(memberId).isEmpty()) {
            return;
        }
        memberAdminRepository.deleteMemberRelations(memberId);
        memberAdminRepository.deleteMember(memberId);
        statusCacheService.evict(memberId);
    }

    private MemberRecord requireMember(UUID memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> ApiException.notFound("Member not found", "member", "not.found"));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String textOrCurrent(String value, String current) {
        return StringUtils.hasText(value) ? value.trim() : current;
    }
}
