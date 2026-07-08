-- liquibase formatted sql

-- changeset zzp84:1770000000000-add-user-interactions
CREATE TABLE member_favorites
(
    member_id  UUID                        NOT NULL,
    video_id   UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_member_favorites PRIMARY KEY (member_id, video_id),
    CONSTRAINT fk_memfav_on_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_memfav_on_video FOREIGN KEY (video_id) REFERENCES videos (id)
);

CREATE INDEX idx_member_favorites_created_at ON member_favorites (created_at DESC);

CREATE TABLE member_watch_histories
(
    member_id        UUID                        NOT NULL,
    video_id         UUID                        NOT NULL,
    progress_seconds INTEGER                     NOT NULL DEFAULT 0,
    duration_seconds INTEGER                     NOT NULL DEFAULT 0,
    last_watched_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_member_watch_histories PRIMARY KEY (member_id, video_id),
    CONSTRAINT fk_memhist_on_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_memhist_on_video FOREIGN KEY (video_id) REFERENCES videos (id)
);

CREATE INDEX idx_watch_history_time ON member_watch_histories (member_id, last_watched_at DESC);