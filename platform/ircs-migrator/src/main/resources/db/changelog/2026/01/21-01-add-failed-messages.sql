-- liquibase formatted sql

-- changeset zzp84:1768900000000-add-failed-messages
CREATE TABLE failed_messages
(
    id              UUID                        NOT NULL,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version         BIGINT,

    queue_name      VARCHAR(255)                NOT NULL,
    routing_key     VARCHAR(255),
    exchange        VARCHAR(255),
    payload         TEXT                        NOT NULL,
    exception_stack TEXT,
    retry_count     INTEGER                     DEFAULT 0,
    status          VARCHAR(50)                 NOT NULL, -- 'PENDING', 'RETRIED', 'DISCARDED'

    CONSTRAINT pk_failed_messages PRIMARY KEY (id)
);

CREATE INDEX idx_failed_msg_queue ON failed_messages (queue_name);
CREATE INDEX idx_failed_msg_status ON failed_messages (status);
CREATE INDEX idx_failed_msg_created ON failed_messages (created_at);