package com.prodigalgal.ircs.identity.repository;


import com.prodigalgal.ircs.identity.domain.MemberRecord;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepository {

    Optional<MemberRecord> findByEmail(String email);

    Optional<MemberRecord> findById(UUID id);

    boolean existsByEmail(String email);

    MemberRecord insert(MemberRecord member);

    MemberRecord update(MemberRecord member);
}
