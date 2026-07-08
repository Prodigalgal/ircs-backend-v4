package com.prodigalgal.ircs.identity.repository;


import com.prodigalgal.ircs.identity.domain.MemberOAuthAccountRecord;
import java.util.Optional;

public interface MemberOAuthAccountRepository {

    Optional<MemberOAuthAccountRecord> findByProviderAndSubject(String provider, String subject);

    MemberOAuthAccountRecord insert(MemberOAuthAccountRecord account);

    MemberOAuthAccountRecord update(MemberOAuthAccountRecord account);
}
