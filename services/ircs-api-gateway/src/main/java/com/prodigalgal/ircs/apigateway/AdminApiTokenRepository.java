package com.prodigalgal.ircs.apigateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AdminApiTokenRepository {

    void insert(AdminApiTokenRecord record);

    List<AdminApiTokenRecord> list();

    Optional<AdminApiTokenRecord> findActiveByHash(String tokenHash, Instant now);

    boolean revoke(UUID id, String revokedBy, Instant now);

    void touch(UUID id, Instant now);
}
