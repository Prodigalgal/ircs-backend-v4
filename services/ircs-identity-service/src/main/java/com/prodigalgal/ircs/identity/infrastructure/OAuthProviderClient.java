package com.prodigalgal.ircs.identity.infrastructure;




import com.prodigalgal.ircs.identity.domain.OAuthUserProfile;
import com.prodigalgal.ircs.identity.domain.OAuthAccessToken;
import com.prodigalgal.ircs.identity.domain.OAuthProviderConfig;
public interface OAuthProviderClient {

    OAuthAccessToken exchangeCode(OAuthProviderConfig config, String code, String codeVerifier);

    OAuthUserProfile fetchUserProfile(OAuthProviderConfig config, OAuthAccessToken token);
}
