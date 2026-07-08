-- liquibase formatted sql

-- changeset zzp84:1770500000000-add-area-tables
CREATE TABLE standard_areas
(
    id         UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version    BIGINT,
    name       VARCHAR(100)                NOT NULL,
    code       VARCHAR(10)                 NOT NULL,
    region     VARCHAR(50),
    CONSTRAINT pk_standard_areas PRIMARY KEY (id),
    CONSTRAINT uc_standard_areas_name UNIQUE (name),
    CONSTRAINT uc_standard_areas_code UNIQUE (code)
);

CREATE TABLE raw_areas
(
    id               UUID                        NOT NULL,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version          BIGINT,
    source_value     VARCHAR(100)                NOT NULL,
    standard_area_id UUID,
    CONSTRAINT pk_raw_areas PRIMARY KEY (id),
    CONSTRAINT uc_raw_areas_source_value UNIQUE (source_value),
    CONSTRAINT fk_raw_areas_on_standard_area FOREIGN KEY (standard_area_id) REFERENCES standard_areas (id)
);

CREATE TABLE video_raw_areas
(
    raw_area_id UUID NOT NULL,
    video_id    UUID NOT NULL,
    CONSTRAINT pk_video_raw_areas PRIMARY KEY (raw_area_id, video_id),
    CONSTRAINT fk_vidrawarea_on_raw_area FOREIGN KEY (raw_area_id) REFERENCES raw_areas (id),
    CONSTRAINT fk_vidrawarea_on_video FOREIGN KEY (video_id) REFERENCES videos (id)
);

CREATE TABLE unified_video_standard_areas
(
    standard_area_id UUID NOT NULL,
    unified_video_id UUID NOT NULL,
    CONSTRAINT pk_unified_video_standard_areas PRIMARY KEY (standard_area_id, unified_video_id),
    CONSTRAINT fk_unividarea_on_std_area FOREIGN KEY (standard_area_id) REFERENCES standard_areas (id),
    CONSTRAINT fk_unividarea_on_uni_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id)
);

-- Indexes
CREATE INDEX idx_video_raw_areas_video_id ON video_raw_areas (video_id);
CREATE INDEX idx_uni_video_areas_uni_id ON unified_video_standard_areas (unified_video_id);