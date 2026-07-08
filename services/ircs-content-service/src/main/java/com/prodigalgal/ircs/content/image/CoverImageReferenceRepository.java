package com.prodigalgal.ircs.content.image;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CoverImageReferenceRepository {

    private static final String SELECT_RAW_VIDEO_IDS =
            "select id from raw_videos where cover_image_id = ?";
    private static final String SELECT_UNIFIED_VIDEO_IDS =
            "select id from unified_videos where cover_image_id = ?";
    private static final String UNLINK_RAW_VIDEO =
            "update raw_videos set cover_image_id = null where cover_image_id = ?";
    private static final String UNLINK_UNIFIED_VIDEO =
            "update unified_videos set cover_image_id = null where cover_image_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public List<UUID> findRawVideoIds(UUID imageId) {
        return jdbcTemplate.query(SELECT_RAW_VIDEO_IDS, (rs, rowNum) -> (UUID) rs.getObject("id"), imageId);
    }

    public List<UUID> findUnifiedVideoIds(UUID imageId) {
        return jdbcTemplate.query(SELECT_UNIFIED_VIDEO_IDS, (rs, rowNum) -> (UUID) rs.getObject("id"), imageId);
    }

    public int unlinkRawVideos(UUID imageId) {
        return jdbcTemplate.update(UNLINK_RAW_VIDEO, imageId);
    }

    public int unlinkUnifiedVideos(UUID imageId) {
        return jdbcTemplate.update(UNLINK_UNIFIED_VIDEO, imageId);
    }
}
