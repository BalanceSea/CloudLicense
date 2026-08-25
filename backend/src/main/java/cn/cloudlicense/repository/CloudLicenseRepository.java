package cn.cloudlicense.repository;

import cn.cloudlicense.domain.License;
import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.domain.UserAccount;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CloudLicenseRepository {
    private final JdbcTemplate jdbc;

    public CloudLicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plugin> findPlugins() {
        return jdbc.query("SELECT * FROM plugins ORDER BY name", this::mapPlugin);
    }

    public Optional<Plugin> findPlugin(UUID id) {
        return jdbc.query("SELECT * FROM plugins WHERE id = ?", this::mapPlugin, id).stream().findFirst();
    }

    public Optional<Plugin> findPlugin(String slug) {
        return jdbc.query("SELECT * FROM plugins WHERE slug = ?", this::mapPlugin, slug).stream().findFirst();
    }

    public Plugin createPlugin(String slug, String name, String description, String message) {
        Plugin plugin = new Plugin(UUID.randomUUID(), slug, name, description, message, OffsetDateTime.now());
        try {
            jdbc.update("INSERT INTO plugins(id, slug, name, description, verification_message, created_at) VALUES(?,?,?,?,?,?)",
                    plugin.id(), plugin.slug(), plugin.name(), plugin.description(),
                    plugin.verificationMessage(), plugin.createdAt());
        } catch (DuplicateKeyException exception) {
            throw exception;
        }
        return plugin;
    }

    public long countLicenses(UUID pluginId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM licenses WHERE plugin_id = ?", Long.class, pluginId);
        return count == null ? 0 : count;
    }

    public List<License> findLicenses(UUID pluginId, int limit, int offset) {
        return jdbc.query("SELECT * FROM licenses WHERE plugin_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::mapLicense, pluginId, limit, offset);
    }

    public Optional<License> findLicenseForUpdate(String pluginSlug, String keyHash) {
        return jdbc.query("""
                SELECT l.* FROM licenses l
                JOIN plugins p ON p.id = l.plugin_id
                WHERE p.slug = ? AND l.key_hash = ?
                FOR UPDATE
                """, this::mapLicense, pluginSlug, keyHash).stream().findFirst();
    }

    public void insertLicense(License license) {
        jdbc.update("""
                INSERT INTO licenses(id, plugin_id, key_hash, key_prefix, status, expires_at,
                                     bound_ip, custom_message, created_at, bound_at, last_verified_at, user_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, license.id(), license.pluginId(), license.keyHash(), license.keyPrefix(), license.status(),
                license.expiresAt(), license.boundIp(), license.customMessage(), license.createdAt(),
                license.boundAt(), license.lastVerifiedAt(), license.userId());
    }

    public void bindLicense(UUID id, String ip, OffsetDateTime now) {
        jdbc.update("UPDATE licenses SET bound_ip = ?, bound_at = ?, last_verified_at = ? WHERE id = ?",
                ip, now, now, id);
    }

    public void touchLicense(UUID id, OffsetDateTime now) {
        jdbc.update("UPDATE licenses SET last_verified_at = ? WHERE id = ?", now, id);
    }

    public boolean updateLicenseStatus(UUID id, String status) {
        return jdbc.update("UPDATE licenses SET status = ? WHERE id = ?", status, id) == 1;
    }

    public boolean unbindLicense(UUID id) {
        return jdbc.update("UPDATE licenses SET bound_ip = NULL, bound_at = NULL WHERE id = ?", id) == 1;
    }

    public boolean assignLicense(UUID licenseId, UUID userId) {
        return jdbc.update("UPDATE licenses SET user_id = ? WHERE id = ? AND user_id IS NULL", userId, licenseId) == 1;
    }

    public List<License> findLicensesByUser(UUID userId) {
        return jdbc.query("SELECT * FROM licenses WHERE user_id = ? ORDER BY created_at DESC",
                this::mapLicense, userId);
    }

    public boolean hasActiveLicense(UUID userId, UUID pluginId, OffsetDateTime now) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM licenses
                WHERE user_id = ? AND plugin_id = ? AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > ?)
                """, Long.class, userId, pluginId, now);
        return count != null && count > 0;
    }

    public boolean unbindUserLicense(UUID licenseId, UUID userId) {
        return jdbc.update("""
                UPDATE licenses SET bound_ip = NULL, bound_at = NULL
                WHERE id = ? AND user_id = ?
                """, licenseId, userId) == 1;
    }

    public UserAccount insertUser(String username, String passwordHash) {
        UserAccount user = new UserAccount(UUID.randomUUID(), username, passwordHash, "ACTIVE", OffsetDateTime.now());
        jdbc.update("INSERT INTO users(id, username, password_hash, status, created_at) VALUES(?,?,?,?,?)",
                user.id(), user.username(), user.passwordHash(), user.status(), user.createdAt());
        return user;
    }

    public Optional<UserAccount> findUserByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", this::mapUser, username)
                .stream().findFirst();
    }

    public Optional<UserAccount> findUserById(UUID userId) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", this::mapUser, userId)
                .stream().findFirst();
    }

    public void insertUserSession(String tokenHash, UUID userId, OffsetDateTime expiresAt, OffsetDateTime now) {
        jdbc.update("INSERT INTO user_sessions(token_hash, user_id, expires_at, created_at) VALUES(?,?,?,?)",
                tokenHash, userId, expiresAt, now);
    }

    public Optional<UserAccount> findUserBySession(String tokenHash, OffsetDateTime now) {
        return jdbc.query("""
                SELECT u.* FROM users u
                JOIN user_sessions s ON s.user_id = u.id
                WHERE s.token_hash = ? AND s.expires_at > ? AND u.status = 'ACTIVE'
                """, this::mapUser, tokenHash, now).stream().findFirst();
    }

    public void deleteUserSession(String tokenHash) {
        jdbc.update("DELETE FROM user_sessions WHERE token_hash = ?", tokenHash);
    }

    public void deleteExpiredUserSessions(OffsetDateTime now) {
        jdbc.update("DELETE FROM user_sessions WHERE expires_at <= ?", now);
    }

    public PluginVersion insertVersion(PluginVersion version) {
        jdbc.update("""
                INSERT INTO plugin_versions(id, plugin_id, version, file_name, stored_path, sha256,
                                            size_bytes, changelog, is_public, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, version.id(), version.pluginId(), version.version(), version.fileName(), version.storedPath(),
                version.sha256(), version.sizeBytes(), version.changelog(), version.isPublic(), version.createdAt());
        return version;
    }

    public List<PluginVersion> findVersions(UUID pluginId, boolean publicOnly) {
        String sql = "SELECT * FROM plugin_versions WHERE plugin_id = ?" +
                (publicOnly ? " AND is_public = TRUE" : "") + " ORDER BY created_at DESC";
        return jdbc.query(sql, this::mapVersion, pluginId);
    }

    public Optional<PluginVersion> findVersion(UUID versionId) {
        return jdbc.query("SELECT * FROM plugin_versions WHERE id = ?", this::mapVersion, versionId)
                .stream().findFirst();
    }

    private Plugin mapPlugin(ResultSet rs, int row) throws SQLException {
        return new Plugin(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("description"), rs.getString("verification_message"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private License mapLicense(ResultSet rs, int row) throws SQLException {
        return new License(rs.getObject("id", UUID.class), rs.getObject("plugin_id", UUID.class),
                rs.getString("key_hash"), rs.getString("key_prefix"), rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("bound_ip"),
                rs.getString("custom_message"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("bound_at", OffsetDateTime.class),
                rs.getObject("last_verified_at", OffsetDateTime.class),
                rs.getObject("user_id", UUID.class));
    }

    private UserAccount mapUser(ResultSet rs, int row) throws SQLException {
        return new UserAccount(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private PluginVersion mapVersion(ResultSet rs, int row) throws SQLException {
        return new PluginVersion(rs.getObject("id", UUID.class), rs.getObject("plugin_id", UUID.class),
                rs.getString("version"), rs.getString("file_name"), rs.getString("stored_path"),
                rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("changelog"),
                rs.getBoolean("is_public"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
