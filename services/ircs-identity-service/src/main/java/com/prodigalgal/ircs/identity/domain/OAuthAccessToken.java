package com.prodigalgal.ircs.identity.domain;

public record OAuthAccessToken(
        String accessToken,
        String tokenType,
        String openId) {
}
