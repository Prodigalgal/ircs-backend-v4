package com.prodigalgal.ircs.identity.domain;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import java.time.Instant;
import java.util.UUID;

public record MemberOAuthAccountRecord(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        long version,
        UUID memberId,
        String provider,
        String providerUserId,
        String providerEmail,
        boolean providerEmailVerified,
        String providerNickname,
        String providerAvatarUrl,
        String accessTokenHash,
        Instant lastLoginAt) {

    public static MemberOAuthAccountRecord newAccount(
            UUID memberId,
            OAuthUserProfile profile,
            String accessTokenHash) {
        Instant now = Instant.now();
        return new MemberOAuthAccountRecord(
                IrcsUuidGenerators.nextId(),
                now,
                now,
                0L,
                memberId,
                profile.provider(),
                profile.subject(),
                profile.email(),
                profile.emailVerified(),
                profile.nickname(),
                profile.avatarUrl(),
                accessTokenHash,
                now);
    }

    public MemberOAuthAccountRecord withProfile(OAuthUserProfile profile, String nextAccessTokenHash) {
        return new MemberOAuthAccountRecord(
                id,
                createdAt,
                Instant.now(),
                version,
                memberId,
                provider,
                providerUserId,
                profile.email(),
                profile.emailVerified(),
                profile.nickname(),
                profile.avatarUrl(),
                nextAccessTokenHash,
                Instant.now());
    }
}
