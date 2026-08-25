package cn.cloudlicense.service;

import cn.cloudlicense.domain.License;
import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.domain.UserAccount;
import cn.cloudlicense.exception.ApiException;
import cn.cloudlicense.repository.CloudLicenseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {
    private static final int SESSION_BYTES = 32;
    private static final int SESSION_DAYS = 7;

    private final CloudLicenseRepository repository;
    private final LicenseKeyHasher licenseHasher;
    private final PluginVersionService versionService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public UserService(CloudLicenseRepository repository, LicenseKeyHasher licenseHasher,
                       PluginVersionService versionService, PasswordEncoder passwordEncoder,
                       TransactionTemplate transactions) {
        this.repository = repository;
        this.licenseHasher = licenseHasher;
        this.versionService = versionService;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.dummyPasswordHash = passwordEncoder.encode("cloudlicense-login-timing-guard");
    }

    public AuthResult register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        UserAccount user;
        try {
            user = repository.insertUser(normalizedUsername, passwordEncoder.encode(password));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用");
        }
        return createSession(user);
    }

    public AuthResult login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        UserAccount user = repository.findUserByUsername(normalizedUsername).orElse(null);
        String passwordHash = user == null ? dummyPasswordHash : user.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);
        if (user == null || !"ACTIVE".equals(user.status()) || !passwordMatches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        return createSession(user);
    }

    public UserAccount authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return repository.findUserBySession(hashToken(rawToken), OffsetDateTime.now()).orElse(null);
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            repository.deleteUserSession(hashToken(rawToken));
        }
    }

    public UserLicenseView claim(UUID userId, String pluginSlug, String rawKey) {
        String slug = pluginSlug == null ? "" : pluginSlug.trim().toLowerCase();
        Plugin plugin = repository.findPlugin(slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "插件不存在"));
        String keyHash = licenseHasher.hash(rawKey);
        return transactions.execute(status -> {
            License license = repository.findLicenseForUpdate(plugin.slug(), keyHash)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LICENSE_NOT_FOUND", "卡密无效"));
            OffsetDateTime now = OffsetDateTime.now();
            if (!"ACTIVE".equals(license.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "LICENSE_REVOKED", "卡密已停用");
            }
            if (license.expiresAt() != null && !license.expiresAt().isAfter(now)) {
                throw new ApiException(HttpStatus.CONFLICT, "LICENSE_EXPIRED", "卡密已过期");
            }
            if (license.userId() != null && !license.userId().equals(userId)) {
                throw new ApiException(HttpStatus.CONFLICT, "LICENSE_ALREADY_CLAIMED", "卡密已被其他用户领取");
            }
            if (license.userId() == null && !repository.assignLicense(license.id(), userId)) {
                throw new ApiException(HttpStatus.CONFLICT, "LICENSE_ALREADY_CLAIMED", "卡密已被其他用户领取");
            }
            return UserLicenseView.from(license);
        });
    }

    public List<UserPluginView> plugins(UUID userId) {
        Map<UUID, List<License>> byPlugin = repository.findLicensesByUser(userId).stream()
                .collect(java.util.stream.Collectors.groupingBy(License::pluginId, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        OffsetDateTime now = OffsetDateTime.now();
        return byPlugin.entrySet().stream().map(entry -> {
            Plugin plugin = repository.findPlugin(entry.getKey()).orElseThrow();
            List<PluginVersion> available = repository.findVersions(plugin.id(), true);
            PluginVersion latest = available.isEmpty() ? null : versionService.latest(available);
            boolean downloadable = entry.getValue().stream().anyMatch(license -> isUsable(license, now));
            return new UserPluginView(plugin.slug(), plugin.name(), plugin.description(),
                    latest == null ? null : LatestRelease.from(latest), downloadable,
                    entry.getValue().stream().map(UserLicenseView::from).toList());
        }).toList();
    }

    public void unbind(UUID userId, UUID licenseId) {
        if (!repository.unbindUserLicense(licenseId, userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LICENSE_NOT_FOUND", "授权记录不存在");
        }
    }

    public PluginVersion requireDownloadableVersion(UUID userId, String pluginSlug) {
        Plugin plugin = repository.findPlugin(pluginSlug.toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "插件不存在"));
        if (!repository.hasActiveLicense(userId, plugin.id(), OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLUGIN_NOT_OWNED", "当前账号没有该插件的有效授权");
        }
        return versionService.latest(plugin.slug());
    }

    private AuthResult createSession(UserAccount user) {
        byte[] tokenBytes = new byte[SESSION_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusDays(SESSION_DAYS);
        repository.deleteExpiredUserSessions(now);
        repository.insertUserSession(hashToken(token), user.id(), expiresAt, now);
        return new AuthResult(token, expiresAt, UserView.from(user));
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private void validatePassword(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码长度不合法");
        }
    }

    private String hashToken(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isUsable(License license, OffsetDateTime now) {
        return "ACTIVE".equals(license.status())
                && (license.expiresAt() == null || license.expiresAt().isAfter(now));
    }

    public record AuthResult(String token, OffsetDateTime expiresAt, UserView user) {
    }

    public record UserView(UUID id, String username, Object createdAt) {
        public static UserView from(UserAccount user) {
            return new UserView(user.id(), user.username(), user.createdAt());
        }
    }

    public record UserLicenseView(UUID id, String key, String status, Object expiresAt, String boundIp,
                                  String customMessage, Object createdAt, Object lastVerifiedAt) {
        static UserLicenseView from(License license) {
            return new UserLicenseView(license.id(), license.keyPrefix() + "••••••••••••", license.status(),
                    license.expiresAt(), license.boundIp(), license.customMessage(), license.createdAt(),
                    license.lastVerifiedAt());
        }
    }

    public record LatestRelease(String version, String sha256, long sizeBytes, String changelog, Object publishedAt) {
        static LatestRelease from(PluginVersion version) {
            return new LatestRelease(version.version(), version.sha256(), version.sizeBytes(),
                    version.changelog(), version.createdAt());
        }
    }

    public record UserPluginView(String slug, String name, String description, LatestRelease latest,
                                 boolean downloadable, List<UserLicenseView> licenses) {
    }
}
