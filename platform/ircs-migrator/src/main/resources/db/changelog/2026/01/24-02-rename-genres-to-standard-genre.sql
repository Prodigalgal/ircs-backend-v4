-- liquibase formatted sql

-- changeset zzp84:rename-genres-to-standard-genre-1
-- 说明：将 genres 表重命名为 standard_genre，与项目 Standard* 命名模式保持一致

-- 1. 删除旧外键约束
ALTER TABLE raw_genres DROP CONSTRAINT IF EXISTS FK_RAW_GENRES_ON_GENRE;
ALTER TABLE unified_video_genres DROP CONSTRAINT IF EXISTS fk_unividgen_on_genre;

-- 2. 删除旧唯一约束
ALTER TABLE genres DROP CONSTRAINT IF EXISTS uc_genres_name;

-- 3. 重命名表
ALTER TABLE genres RENAME TO standard_genre;

-- 4. 重命名列 (raw_genres 中的 genre_id 改为 standard_genre_id)
ALTER TABLE raw_genres RENAME COLUMN genre_id TO standard_genre_id;

-- 5. 重新创建约束
ALTER TABLE standard_genre ADD CONSTRAINT uc_standard_genre_name UNIQUE (name);

ALTER TABLE raw_genres ADD CONSTRAINT FK_RAW_GENRES_ON_STANDARD_GENRE 
    FOREIGN KEY (standard_genre_id) REFERENCES standard_genre (id);

ALTER TABLE unified_video_genres ADD CONSTRAINT fk_unividgen_on_standard_genre 
    FOREIGN KEY (genre_id) REFERENCES standard_genre (id);
