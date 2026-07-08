package com.prodigalgal.ircs.identity.domain;

public record OAuthUserProfile(
        String provider,
        String subject,
        String email,
        boolean emailVerified,
        String nickname,
        String avatarUrl) {
}
