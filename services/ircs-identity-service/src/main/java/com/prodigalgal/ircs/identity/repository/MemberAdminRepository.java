package com.prodigalgal.ircs.identity.repository;






import com.prodigalgal.ircs.identity.dto.PageBounds;
import com.prodigalgal.ircs.identity.dto.PageResponse;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.identity.dto.HistoryRecordResponse;
import java.util.UUID;

public interface MemberAdminRepository {

    PageResponse<MemberRecord> findMembers(
            String keyword,
            MemberStatus status,
            Boolean adultContentAllowed,
            Integer minPoints,
            Integer maxPoints,
            PageBounds bounds,
            String sort);

    boolean existsByEmailExcludingId(String email, UUID excludedId);

    PageResponse<HistoryRecordResponse> findFavorites(UUID memberId, PageBounds bounds);

    PageResponse<HistoryRecordResponse> findHistory(UUID memberId, PageBounds bounds);

    void deleteMemberRelations(UUID memberId);

    int deleteMember(UUID memberId);
}
