-- liquibase formatted sql

-- changeset zzp84:20260701-add-member-oauth-accounts
CREATE TABLE IF NOT EXISTS member_oauth_accounts (
    id UUID NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    member_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255),
    provider_email_verified BOOLEAN DEFAULT FALSE NOT NULL,
    provider_nickname VARCHAR(255),
    provider_avatar_url VARCHAR(1024),
    access_token_hash VARCHAR(128),
    last_login_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_member_oauth_accounts PRIMARY KEY (id),
    CONSTRAINT fk_member_oauth_accounts_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT uc_member_oauth_provider_subject UNIQUE (provider, provider_user_id)
);

CREATE INDEX IF NOT EXISTS idx_member_oauth_accounts_member_id ON member_oauth_accounts (member_id);
CREATE INDEX IF NOT EXISTS idx_member_oauth_accounts_provider_email ON member_oauth_accounts (provider, lower(provider_email));

-- changeset zzp84:20260701-update-huawai-prod-oauth-domain
UPDATE system_configs
   SET config_value = 'https://huawai.mnnu.eu.org',
       updated_at = now(),
       version = coalesce(version, 0) + 1
 WHERE config_key IN ('app.frontend.url', 'member.oauth.redirect-base-url')
   AND config_value = 'https://huawai.sophia.fr.eu.org';
