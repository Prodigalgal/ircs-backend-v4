-- liquibase formatted sql

-- changeset zzp84:1769000000000-add-members
CREATE TABLE members
(
    id            UUID                        NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version       BIGINT,

    email         VARCHAR(255)                NOT NULL,
    password_hash VARCHAR(255)                NOT NULL,
    nickname      VARCHAR(100),
    avatar_url    VARCHAR(1024),
    role          VARCHAR(50)                 NOT NULL, -- 'MEMBER'
    status        VARCHAR(50)                 NOT NULL, -- 'ACTIVE', 'BANNED'

    CONSTRAINT pk_members PRIMARY KEY (id),
    CONSTRAINT uc_members_email UNIQUE (email)
);

CREATE INDEX idx_members_email ON members (email);