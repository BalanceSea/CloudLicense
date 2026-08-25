CREATE TABLE IF NOT EXISTS plugins (
    id UUID PRIMARY KEY,
    slug VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    verification_message VARCHAR(300) NOT NULL DEFAULT '授权验证通过',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS licenses (
    id UUID PRIMARY KEY,
    plugin_id UUID NOT NULL REFERENCES plugins(id),
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    bound_ip VARCHAR(45),
    custom_message VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    bound_at TIMESTAMP WITH TIME ZONE,
    last_verified_at TIMESTAMP WITH TIME ZONE
);

ALTER TABLE licenses ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id);

CREATE INDEX IF NOT EXISTS idx_licenses_plugin_created ON licenses(plugin_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_licenses_plugin_status ON licenses(plugin_id, status);
CREATE INDEX IF NOT EXISTS idx_licenses_user_plugin ON licenses(user_id, plugin_id);

CREATE TABLE IF NOT EXISTS user_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires ON user_sessions(expires_at);

CREATE TABLE IF NOT EXISTS plugin_versions (
    id UUID PRIMARY KEY,
    plugin_id UUID NOT NULL REFERENCES plugins(id),
    version VARCHAR(64) NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    stored_path VARCHAR(500) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    changelog VARCHAR(2000) NOT NULL DEFAULT '',
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_plugin_version UNIQUE(plugin_id, version)
);

CREATE INDEX IF NOT EXISTS idx_versions_plugin_created ON plugin_versions(plugin_id, created_at DESC);

MERGE INTO plugins (id, slug, name, description, verification_message, created_at)
KEY(slug) VALUES (
    UUID '10000000-0000-0000-0000-000000000001', 'cloudfashion', 'CloudFashion', '角色时装与外观管理插件', 'CloudFashion 授权验证通过', CURRENT_TIMESTAMP
);

MERGE INTO plugins (id, slug, name, description, verification_message, created_at)
KEY(slug) VALUES (
    UUID '10000000-0000-0000-0000-000000000002', 'cloudchest', 'CloudChest', '云端仓库与物品管理插件', 'CloudChest 授权验证通过', CURRENT_TIMESTAMP
);
