-- liquibase formatted sql

-- changeset zzp84:1767691208700-1
CREATE TABLE actors
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_actors PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-2
CREATE TABLE categories
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(100)                NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-3
CREATE TABLE collection_tasks
(
    id                  UUID                        NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version             BIGINT,
    name                VARCHAR(255)                NOT NULL,
    status              VARCHAR(50)                 NOT NULL,
    enabled             BOOLEAN                     NOT NULL,
    cron_expression     VARCHAR(100),
    task_type           VARCHAR(50)                 NOT NULL,
    start_page          INTEGER,
    end_page            INTEGER,
    current_page        INTEGER,
    filter_type         VARCHAR(100),
    filter_hours        INTEGER,
    filter_keywords     VARCHAR(255),
    duplicate_strategy  VARCHAR(50)                 NOT NULL,
    request_delay_type  VARCHAR(50)                 NOT NULL,
    fixed_delay_ms      INTEGER                     NOT NULL,
    random_delay_min_ms INTEGER                     NOT NULL,
    random_delay_max_ms INTEGER                     NOT NULL,
    timeout_ms          INTEGER                     NOT NULL,
    max_retries         INTEGER                     NOT NULL,
    user_agent          TEXT,
    enable_random_ua    BOOLEAN                     NOT NULL,
    use_custom_proxy    BOOLEAN                     NOT NULL,
    proxy_type          VARCHAR(10),
    proxy_host          VARCHAR(255),
    proxy_port          INTEGER,
    proxy_username      VARCHAR(255),
    proxy_password      VARCHAR(255),
    headers             JSONB,
    last_execution_time TIMESTAMP WITHOUT TIME ZONE,
    stat_start_time     TIMESTAMP WITHOUT TIME ZONE,
    stat_end_time       TIMESTAMP WITHOUT TIME ZONE,
    stat_total_found    BIGINT,
    stat_processed      BIGINT,
    stat_success        BIGINT,
    stat_failed         BIGINT,
    last_error_message  TEXT,
    data_source_id      UUID                        NOT NULL,
    stat_inserted       BIGINT,
    stat_updated        BIGINT,
    stat_ignored        BIGINT,
    CONSTRAINT pk_collection_tasks PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-4
CREATE TABLE cover_images
(
    id               UUID                        NOT NULL,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version          BIGINT,
    storage_type     VARCHAR(50)                 NOT NULL,
    original_url     VARCHAR(2048)               NOT NULL,
    storage_path     VARCHAR(2048),
    file_hash        VARCHAR(64),
    file_size        BIGINT,
    mime_type        VARCHAR(100),
    source_domain_id UUID                        NOT NULL,
    status           VARCHAR(50)                 NOT NULL,
    retry_count      INTEGER,
    next_retry_time  TIMESTAMP WITHOUT TIME ZONE,
    last_error       TEXT,
    CONSTRAINT pk_cover_images PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-5
CREATE TABLE data_source_categories
(
    id             UUID                        NOT NULL,
    created_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version        BIGINT,
    data_source_id UUID                        NOT NULL,
    source_code    VARCHAR(100)                NOT NULL,
    source_name    VARCHAR(255),
    category_id    UUID,
    CONSTRAINT pk_data_source_categories PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-6
CREATE TABLE data_sources
(
    id            UUID                        NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version       BIGINT,
    name          VARCHAR(255)                NOT NULL,
    base_url      VARCHAR(1024)               NOT NULL,
    list_path     VARCHAR(1024)               NOT NULL,
    list_params   JSONB,
    detail_path   VARCHAR(1024)               NOT NULL,
    detail_params JSONB,
    field_mapping JSONB,
    CONSTRAINT pk_data_sources PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-7
CREATE TABLE directors
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_directors PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-8
CREATE TABLE episodes
(
    id               UUID                        NOT NULL,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version          BIGINT,
    name             VARCHAR(255)                NOT NULL,
    url              VARCHAR(2048)               NOT NULL,
    playlist_id      UUID                        NOT NULL,
    source_domain_id UUID,
    CONSTRAINT pk_episodes PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-9
CREATE TABLE genres
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(100)                NOT NULL,
    CONSTRAINT pk_genres PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-10
CREATE TABLE playlists
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(100)                NOT NULL,
    video_id   UUID                        NOT NULL,
    CONSTRAINT pk_playlists PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-11
CREATE TABLE raw_genres
(
    id           UUID                        NOT NULL,
    created_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version      BIGINT,
    source_value VARCHAR(100)                NOT NULL,
    genre_id     UUID,
    CONSTRAINT pk_raw_genres PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-12
CREATE TABLE raw_languages
(
    id                   UUID                        NOT NULL,
    created_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version              BIGINT,
    source_value         VARCHAR(100)                NOT NULL,
    standard_language_id UUID,
    CONSTRAINT pk_raw_languages PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-13
CREATE TABLE source_domains
(
    id             UUID                        NOT NULL,
    created_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version        BIGINT,
    domain_hash    VARCHAR(64)                 NOT NULL,
    domain_value   VARCHAR(255)                NOT NULL,
    remark         VARCHAR(255),
    data_source_id UUID,
    CONSTRAINT pk_source_domains PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-14
CREATE TABLE standard_languages
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(100)                NOT NULL,
    code       VARCHAR(20),
    CONSTRAINT pk_standard_languages PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-15
CREATE TABLE system_configs
(
    id           UUID                        NOT NULL,
    created_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version      BIGINT,
    config_key   VARCHAR(100)                NOT NULL,
    config_value VARCHAR(1024),
    description  VARCHAR(255),
    CONSTRAINT pk_system_configs PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-16
CREATE TABLE unified_video_actors
(
    actor_id         UUID NOT NULL,
    unified_video_id UUID NOT NULL,
    CONSTRAINT pk_unified_video_actors PRIMARY KEY (actor_id, unified_video_id)
);

-- changeset zzp84:1767691208700-17
CREATE TABLE unified_video_directors
(
    director_id      UUID NOT NULL,
    unified_video_id UUID NOT NULL,
    CONSTRAINT pk_unified_video_directors PRIMARY KEY (director_id, unified_video_id)
);

-- changeset zzp84:1767691208700-18
CREATE TABLE unified_video_genres
(
    genre_id         UUID NOT NULL,
    unified_video_id UUID NOT NULL,
    CONSTRAINT pk_unified_video_genres PRIMARY KEY (genre_id, unified_video_id)
);

-- changeset zzp84:1767691208700-19
CREATE TABLE unified_video_standard_languages
(
    standard_language_id UUID NOT NULL,
    unified_video_id     UUID NOT NULL,
    CONSTRAINT pk_unified_video_standard_languages PRIMARY KEY (standard_language_id, unified_video_id)
);

-- changeset zzp84:1767691208700-20
CREATE TABLE unified_videos
(
    id                 UUID                        NOT NULL,
    created_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version            BIGINT,
    title              VARCHAR(255)                NOT NULL,
    cover_image_id     UUID,
    description        TEXT,
    year               VARCHAR(20),
    area               VARCHAR(50),
    score              DECIMAL(3, 1),
    published_at       date,
    douban_id          VARCHAR(20),
    tmdb_id            VARCHAR(20),
    imdb_id            VARCHAR(20),
    rotten_tomatoes_id VARCHAR(50),
    locked_fields      JSONB,
    category_id        UUID,
    CONSTRAINT pk_unified_videos PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-21
CREATE TABLE video_actors
(
    actor_id UUID NOT NULL,
    video_id UUID NOT NULL,
    CONSTRAINT pk_video_actors PRIMARY KEY (actor_id, video_id)
);

-- changeset zzp84:1767691208700-22
CREATE TABLE video_directors
(
    director_id UUID NOT NULL,
    video_id    UUID NOT NULL,
    CONSTRAINT pk_video_directors PRIMARY KEY (director_id, video_id)
);

-- changeset zzp84:1767691208700-23
CREATE TABLE video_raw_genres
(
    raw_genre_id UUID NOT NULL,
    video_id     UUID NOT NULL,
    CONSTRAINT pk_video_raw_genres PRIMARY KEY (raw_genre_id, video_id)
);

-- changeset zzp84:1767691208700-24
CREATE TABLE video_raw_languages
(
    raw_language_id UUID NOT NULL,
    video_id        UUID NOT NULL,
    CONSTRAINT pk_video_raw_languages PRIMARY KEY (raw_language_id, video_id)
);

-- changeset zzp84:1767691208700-25
CREATE TABLE video_resolver_sources
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(255)                NOT NULL,
    is_active  BOOLEAN                     NOT NULL,
    remark     VARCHAR(255),
    lines      JSONB,
    CONSTRAINT pk_video_resolver_sources PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-26
CREATE TABLE videos
(
    id                      UUID                        NOT NULL,
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version                 BIGINT,
    source_vid              VARCHAR(255)                NOT NULL,
    source_hash             VARCHAR(64)                 NOT NULL,
    data_hash               VARCHAR(64),
    title                   VARCHAR(255)                NOT NULL,
    cover_image_id          UUID,
    description             TEXT,
    year                    VARCHAR(20),
    area                    VARCHAR(50),
    raw_language_str        VARCHAR(255),
    remarks                 VARCHAR(255),
    score                   DECIMAL(3, 1),
    published_at            date,
    douban_id               VARCHAR(20),
    tmdb_id                 VARCHAR(20),
    imdb_id                 VARCHAR(20),
    rotten_tomatoes_id      VARCHAR(50),
    locked_fields           JSONB,
    data_source_category_id UUID,
    unified_video_id        UUID,
    enrichment_status       VARCHAR(50)                 NOT NULL,
    enrichment_retry_count  INTEGER,
    normalization_status    VARCHAR(50)                 NOT NULL,
    raw_metadata            JSONB,
    CONSTRAINT pk_videos PRIMARY KEY (id)
);

-- changeset zzp84:1767691208700-27
ALTER TABLE cover_images
    ADD CONSTRAINT idx_cover_images_unique_origin_domain UNIQUE (original_url, source_domain_id);

-- changeset zzp84:1767691208700-28
ALTER TABLE playlists
    ADD CONSTRAINT uc_5c018c02068bd20b23fb85b85 UNIQUE (video_id, name);

-- changeset zzp84:1767691208700-29
ALTER TABLE actors
    ADD CONSTRAINT uc_actors_name UNIQUE (name);

-- changeset zzp84:1767691208700-30
ALTER TABLE categories
    ADD CONSTRAINT uc_categories_name UNIQUE (name);

-- changeset zzp84:1767691208700-31
ALTER TABLE collection_tasks
    ADD CONSTRAINT uc_collection_tasks_name UNIQUE (name);

-- changeset zzp84:1767691208700-32
ALTER TABLE data_sources
    ADD CONSTRAINT uc_data_sources_name UNIQUE (name);

-- changeset zzp84:1767691208700-33
ALTER TABLE directors
    ADD CONSTRAINT uc_directors_name UNIQUE (name);

-- changeset zzp84:1767691208700-34
ALTER TABLE data_source_categories
    ADD CONSTRAINT uc_f10b68cf2612aa087a409ab14 UNIQUE (data_source_id, source_code);

-- changeset zzp84:1767691208700-35
ALTER TABLE genres
    ADD CONSTRAINT uc_genres_name UNIQUE (name);

-- changeset zzp84:1767691208700-36
ALTER TABLE raw_genres
    ADD CONSTRAINT uc_raw_genres_source_value UNIQUE (source_value);

-- changeset zzp84:1767691208700-37
ALTER TABLE raw_languages
    ADD CONSTRAINT uc_raw_languages_source_value UNIQUE (source_value);

-- changeset zzp84:1767691208700-38
ALTER TABLE source_domains
    ADD CONSTRAINT uc_source_domains_domain_hash UNIQUE (domain_hash);

-- changeset zzp84:1767691208700-39
ALTER TABLE standard_languages
    ADD CONSTRAINT uc_standard_languages_name UNIQUE (name);

-- changeset zzp84:1767691208700-40
ALTER TABLE system_configs
    ADD CONSTRAINT uc_system_configs_config_key UNIQUE (config_key);

-- changeset zzp84:1767691208700-41
ALTER TABLE video_resolver_sources
    ADD CONSTRAINT uc_video_resolver_sources_name UNIQUE (name);

-- changeset zzp84:1767691208700-42
ALTER TABLE videos
    ADD CONSTRAINT uc_videos_source_hash UNIQUE (source_hash);

-- changeset zzp84:1767691208700-43
ALTER TABLE collection_tasks
    ADD CONSTRAINT FK_COLLECTION_TASKS_ON_DATA_SOURCE FOREIGN KEY (data_source_id) REFERENCES data_sources (id);

-- changeset zzp84:1767691208700-44
ALTER TABLE cover_images
    ADD CONSTRAINT FK_COVER_IMAGES_ON_SOURCE_DOMAIN FOREIGN KEY (source_domain_id) REFERENCES source_domains (id);

-- changeset zzp84:1767691208700-45
ALTER TABLE data_source_categories
    ADD CONSTRAINT FK_DATA_SOURCE_CATEGORIES_ON_CATEGORY FOREIGN KEY (category_id) REFERENCES categories (id);

-- changeset zzp84:1767691208700-46
ALTER TABLE data_source_categories
    ADD CONSTRAINT FK_DATA_SOURCE_CATEGORIES_ON_DATA_SOURCE FOREIGN KEY (data_source_id) REFERENCES data_sources (id);

-- changeset zzp84:1767691208700-47
ALTER TABLE episodes
    ADD CONSTRAINT FK_EPISODES_ON_PLAYLIST FOREIGN KEY (playlist_id) REFERENCES playlists (id);

-- changeset zzp84:1767691208700-48
ALTER TABLE episodes
    ADD CONSTRAINT FK_EPISODES_ON_SOURCE_DOMAIN FOREIGN KEY (source_domain_id) REFERENCES source_domains (id);

-- changeset zzp84:1767691208700-49
ALTER TABLE playlists
    ADD CONSTRAINT FK_PLAYLISTS_ON_VIDEO FOREIGN KEY (video_id) REFERENCES videos (id);

-- changeset zzp84:1767691208700-50
ALTER TABLE raw_genres
    ADD CONSTRAINT FK_RAW_GENRES_ON_GENRE FOREIGN KEY (genre_id) REFERENCES genres (id);

-- changeset zzp84:1767691208700-51
ALTER TABLE raw_languages
    ADD CONSTRAINT FK_RAW_LANGUAGES_ON_STANDARD_LANGUAGE FOREIGN KEY (standard_language_id) REFERENCES standard_languages (id);

-- changeset zzp84:1767691208700-52
ALTER TABLE source_domains
    ADD CONSTRAINT FK_SOURCE_DOMAINS_ON_DATA_SOURCE FOREIGN KEY (data_source_id) REFERENCES data_sources (id);

-- changeset zzp84:1767691208700-53
ALTER TABLE unified_videos
    ADD CONSTRAINT FK_UNIFIED_VIDEOS_ON_CATEGORY FOREIGN KEY (category_id) REFERENCES categories (id);

-- changeset zzp84:1767691208700-54
ALTER TABLE unified_videos
    ADD CONSTRAINT FK_UNIFIED_VIDEOS_ON_COVER_IMAGE FOREIGN KEY (cover_image_id) REFERENCES cover_images (id);

-- changeset zzp84:1767691208700-55
ALTER TABLE videos
    ADD CONSTRAINT FK_VIDEOS_ON_COVER_IMAGE FOREIGN KEY (cover_image_id) REFERENCES cover_images (id);

-- changeset zzp84:1767691208700-56
ALTER TABLE videos
    ADD CONSTRAINT FK_VIDEOS_ON_DATA_SOURCE_CATEGORY FOREIGN KEY (data_source_category_id) REFERENCES data_source_categories (id);

-- changeset zzp84:1767691208700-57
ALTER TABLE videos
    ADD CONSTRAINT FK_VIDEOS_ON_UNIFIED_VIDEO FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id);

-- changeset zzp84:1767691208700-58
ALTER TABLE unified_video_actors
    ADD CONSTRAINT fk_unividact_on_actor FOREIGN KEY (actor_id) REFERENCES actors (id);

-- changeset zzp84:1767691208700-59
ALTER TABLE unified_video_actors
    ADD CONSTRAINT fk_unividact_on_unified_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id);

-- changeset zzp84:1767691208700-60
ALTER TABLE unified_video_directors
    ADD CONSTRAINT fk_unividdir_on_director FOREIGN KEY (director_id) REFERENCES directors (id);

-- changeset zzp84:1767691208700-61
ALTER TABLE unified_video_directors
    ADD CONSTRAINT fk_unividdir_on_unified_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id);

-- changeset zzp84:1767691208700-62
ALTER TABLE unified_video_genres
    ADD CONSTRAINT fk_unividgen_on_genre FOREIGN KEY (genre_id) REFERENCES genres (id);

-- changeset zzp84:1767691208700-63
ALTER TABLE unified_video_genres
    ADD CONSTRAINT fk_unividgen_on_unified_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id);

-- changeset zzp84:1767691208700-64
ALTER TABLE unified_video_standard_languages
    ADD CONSTRAINT fk_unividstalan_on_standard_language FOREIGN KEY (standard_language_id) REFERENCES standard_languages (id);

-- changeset zzp84:1767691208700-65
ALTER TABLE unified_video_standard_languages
    ADD CONSTRAINT fk_unividstalan_on_unified_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id);

-- changeset zzp84:1767691208700-66
ALTER TABLE video_actors
    ADD CONSTRAINT fk_vidact_on_actor FOREIGN KEY (actor_id) REFERENCES actors (id);

-- changeset zzp84:1767691208700-67
ALTER TABLE video_actors
    ADD CONSTRAINT fk_vidact_on_video FOREIGN KEY (video_id) REFERENCES videos (id);

-- changeset zzp84:1767691208700-68
ALTER TABLE video_directors
    ADD CONSTRAINT fk_viddir_on_director FOREIGN KEY (director_id) REFERENCES directors (id);

-- changeset zzp84:1767691208700-69
ALTER TABLE video_directors
    ADD CONSTRAINT fk_viddir_on_video FOREIGN KEY (video_id) REFERENCES videos (id);

-- changeset zzp84:1767691208700-70
ALTER TABLE video_raw_genres
    ADD CONSTRAINT fk_vidrawgen_on_raw_genre FOREIGN KEY (raw_genre_id) REFERENCES raw_genres (id);

-- changeset zzp84:1767691208700-71
ALTER TABLE video_raw_genres
    ADD CONSTRAINT fk_vidrawgen_on_video FOREIGN KEY (video_id) REFERENCES videos (id);

-- changeset zzp84:1767691208700-72
ALTER TABLE video_raw_languages
    ADD CONSTRAINT fk_vidrawlan_on_raw_language FOREIGN KEY (raw_language_id) REFERENCES raw_languages (id);

-- changeset zzp84:1767691208700-73
ALTER TABLE video_raw_languages
    ADD CONSTRAINT fk_vidrawlan_on_video FOREIGN KEY (video_id) REFERENCES videos (id);

