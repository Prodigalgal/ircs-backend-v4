package com.prodigalgal.ircs.content.auxiliary.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuxiliaryAdminDtos {

    private AuxiliaryAdminDtos() {
    }

    public record EpisodeRequest(
            UUID id,
            @Size(max = 255, message = "Episode name cannot exceed 255 characters.")
            String name,
            String url) {
    }

    public record EpisodeResponse(
            UUID id,
            String name,
            String url) {
    }

    public record PlaylistRequest(
            UUID id,
            @NotBlank(message = "Playlist name cannot be blank.")
            @Size(max = 100, message = "Playlist name cannot exceed 100 characters.")
            String name,
            @NotNull(message = "Playlist must be associated with a video.")
            UUID videoId,
            String videoTitle,
            @Valid
            List<EpisodeRequest> episodes) {
    }

    public record PlaylistUpdateRequest(
            UUID id,
            @NotBlank(message = "Playlist name cannot be blank.")
            @Size(max = 100, message = "Playlist name cannot exceed 100 characters.")
            String name,
            @Valid
            List<EpisodeRequest> episodes) {
    }

    public record PlaylistCardResponse(
            UUID id,
            String name,
            UUID videoId,
            int episodeCount) {
    }

    public record PlaylistDetailResponse(
            UUID id,
            String name,
            UUID videoId,
            String videoTitle,
            List<EpisodeResponse> episodes,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SourceDomainRequest(
            UUID id,
            @Size(max = 64, message = "Domain hash cannot exceed 64 characters.")
            String domainHash,
            @NotBlank(message = "Domain value cannot be blank.")
            @Size(max = 255, message = "Domain value cannot exceed 255 characters.")
            String domainValue,
            @Size(max = 255, message = "Remark cannot exceed 255 characters.")
            String remark,
            UUID dataSourceId,
            String dataSourceName) {
    }

    public record SourceDomainResponse(
            UUID id,
            String domainHash,
            String domainValue,
            String remark,
            UUID dataSourceId,
            String dataSourceName,
            boolean adultRestricted,
            Instant createdAt,
            Instant updatedAt) {

        public SourceDomainResponse(
                UUID id,
                String domainHash,
                String domainValue,
                String remark,
                UUID dataSourceId,
                String dataSourceName,
                Instant createdAt,
                Instant updatedAt) {
            this(id, domainHash, domainValue, remark, dataSourceId, dataSourceName, false, createdAt, updatedAt);
        }
    }

    public record ResolverLine(
            String name,
            String url) {
    }

    public record ResolverRequest(
            UUID id,
            @NotBlank(message = "Resolver name cannot be blank.")
            @Size(max = 255, message = "Resolver name cannot exceed 255 characters.")
            String name,
            Boolean isActive,
            Boolean active,
            @Size(max = 255, message = "Remark cannot exceed 255 characters.")
            String remark,
            @Valid
            List<ResolverLine> lines) {

        public boolean activeValue() {
            if (isActive != null) {
                return isActive;
            }
            return active == null || active;
        }
    }

    public record ResolverResponse(
            UUID id,
            String name,
            boolean isActive,
            boolean active,
            String remark,
            JsonNode lines) {
    }
}
