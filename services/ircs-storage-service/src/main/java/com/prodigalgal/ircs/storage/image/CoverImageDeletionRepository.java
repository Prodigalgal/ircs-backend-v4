package com.prodigalgal.ircs.storage.image;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CoverImageDeletionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<CoverImageRecord> findById(UUID id) {
        String sql = """
                select id, storage_type, storage_path
                from cover_images
                where id = :id
                """;
        return jdbcTemplate.query(sql, Map.of("id", id), (rs, rowNum) -> new CoverImageRecord(
                        rs.getObject("id", UUID.class),
                        CoverImageStorageType.valueOf(rs.getString("storage_type")),
                        rs.getString("storage_path")))
                .stream()
                .findFirst();
    }

    public int deleteById(UUID id) {
        return jdbcTemplate.update("delete from cover_images where id = :id", Map.of("id", id));
    }
}
