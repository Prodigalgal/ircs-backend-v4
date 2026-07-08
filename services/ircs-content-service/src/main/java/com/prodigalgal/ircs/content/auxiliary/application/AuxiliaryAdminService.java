package com.prodigalgal.ircs.content.auxiliary.application;

import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistCardResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistDetailResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.PlaylistUpdateRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.ResolverResponse;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainRequest;
import com.prodigalgal.ircs.content.auxiliary.dto.AuxiliaryAdminDtos.SourceDomainResponse;
import com.prodigalgal.ircs.content.auxiliary.infrastructure.JdbcAuxiliaryAdminRepository;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuxiliaryAdminService {

    private final JdbcAuxiliaryAdminRepository repository;

    @Transactional
    public PlaylistDetailResponse createPlaylist(PlaylistRequest request) {
        if (request.id() != null) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "A new playlist cannot already have an ID");
        }
        return repository.createPlaylist(request);
    }

    @Transactional
    public PlaylistDetailResponse updatePlaylist(UUID id, PlaylistUpdateRequest request) {
        validateBodyId(id, request.id(), "playlist");
        return repository.updatePlaylist(id, request);
    }

    public Page<PlaylistCardResponse> findPlaylists(Pageable pageable, String name, String videoTitle) {
        return repository.findPlaylists(pageable, name, videoTitle);
    }

    public Optional<PlaylistDetailResponse> findPlaylist(UUID id) {
        return repository.findPlaylist(id);
    }

    @Transactional
    public void deletePlaylist(UUID id) {
        repository.deletePlaylist(id);
    }

    public Page<SourceDomainResponse> findSourceDomains(Pageable pageable, String keyword, UUID dataSourceId) {
        return repository.findSourceDomains(pageable, keyword, dataSourceId);
    }

    public Optional<SourceDomainResponse> findSourceDomain(UUID id) {
        return repository.findSourceDomain(id);
    }

    @Transactional
    public SourceDomainResponse updateSourceDomain(UUID id, SourceDomainRequest request) {
        validateBodyId(id, request.id(), "sourceDomain");
        return repository.updateSourceDomain(id, request);
    }

    @Transactional
    public ResolverResponse createResolver(ResolverRequest request) {
        if (request.id() != null) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "A new resolver cannot already have an ID");
        }
        return repository.createResolver(request);
    }

    @Transactional
    public ResolverResponse updateResolver(UUID id, ResolverRequest request) {
        return repository.updateResolver(id, request);
    }

    public Page<ResolverResponse> findResolvers(Pageable pageable) {
        return repository.findResolvers(pageable);
    }

    public List<ResolverResponse> findActiveResolvers() {
        return repository.findActiveResolvers();
    }

    public Optional<ResolverResponse> findResolver(UUID id) {
        return repository.findResolver(id);
    }

    @Transactional
    public void deleteResolver(UUID id) {
        repository.deleteResolver(id);
    }

    private void validateBodyId(UUID pathId, UUID bodyId, String entity) {
        if (bodyId == null) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "Invalid id");
        }
        if (!Objects.equals(pathId, bodyId)) {
            throw new ContentApiException(HttpStatus.BAD_REQUEST, "Invalid ID for " + entity);
        }
    }
}
