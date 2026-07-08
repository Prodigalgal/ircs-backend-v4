package com.prodigalgal.ircs.interaction;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InteractionQueryService {

    private final JdbcInteractionRepository repository;
    private final InteractionReadModelCache readModelCache;

    @Transactional(readOnly = true)
    public PageResponse<UserMessageResponse> publicWall(PageBounds bounds) {
        return readModelCache.publicFeedbackWall(bounds, () -> repository.findPublicMessages(bounds));
    }

    @Transactional(readOnly = true)
    public PageResponse<InteractionRecordResponse> history(UUID memberId, PageBounds bounds) {
        return repository.findHistory(memberId, bounds);
    }

    @Transactional(readOnly = true)
    public PageResponse<InteractionRecordResponse> favorites(UUID memberId, PageBounds bounds) {
        return repository.findFavorites(memberId, bounds);
    }
}
