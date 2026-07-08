package com.prodigalgal.ircs.interaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record UserMessageResponse(
        UUID id,
        UUID memberId,
        String memberNickname,
        String memberEmail,
        String memberAvatarUrl,
        String content,
        String reply,
        String status,
        @JsonProperty("isPublic") boolean publicMessage,
        Instant createdAt,
        Instant updatedAt) {

    @JsonProperty("public")
    public boolean publicValue() {
        return publicMessage;
    }
}
