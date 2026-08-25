package cn.cloudlicense.service;

import cn.cloudlicense.domain.License;
import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.exception.ApiException;
import cn.cloudlicense.repository.CloudLicenseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LicenseService {
    private static final char[] KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MAX_GENERATE_COUNT = 100;

    private final CloudLicenseRepository repository;
    private final LicenseKeyHasher hasher;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public LicenseService(CloudLicenseRepository repository, LicenseKeyHasher hasher,
                          TransactionTemplate transactions) {
        this.repository = repository;
        this.hasher = hasher;
        this.transactions = transactions;
    }

    public GeneratedBatch generate(UUID pluginId, int count, Integer durationDays, String customMessage) {
        Plugin plugin = repository.findPlugin(pluginId).orElseThrow(() -> notFound("PLUGIN_NOT_FOUND", "插件不存在"));
        if (count < 1 || count > MAX_GENERATE_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COUNT", "单次生成数量必须在 1 到 100 之间");
        }
        if (durationDays != null && (durationDays < 1 || durationDays > 3650)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DURATION", "有效期必须在 1 到 3650 天之间");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = durationDays == null ? null : now.plusDays(durationDays);
        List<String> plaintextKeys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            insertUniqueKey(plugin.id(), expiresAt, customMessage, now, plaintextKeys);
        }
        return new GeneratedBatch(plugin.slug(), plaintextKeys, expiresAt);
    }

    public VerificationResult verify(String pluginSlug, String rawKey, String requestIp) {
        String slug = pluginSlug == null ? "" : pluginSlug.trim().toLowerCase();
        Plugin plugin = repository.findPlugin(slug)
                .orElseThrow(() -> notFound("PLUGIN_NOT_FOUND", "插件不存在"));
        String hash = hasher.hash(rawKey);
        return transactions.execute(status -> {
            License license = repository.findLicenseForUpdate(plugin.slug(), hash).orElse(null);
            if (license == null) {
                return VerificationResult.failure("LICENSE_NOT_FOUND", "卡密无效");
            }
            if (!"ACTIVE".equals(license.status())) {
                return VerificationResult.failure("LICENSE_REVOKED", "卡密已停用");
            }

            OffsetDateTime now = OffsetDateTime.now();
            if (license.expiresAt() != null && !license.expiresAt().isAfter(now)) {
                return VerificationResult.failure("LICENSE_EXPIRED", "卡密已过期");
            }
            if (license.boundIp() == null) {
                repository.bindLicense(license.id(), requestIp, now);
            } else if (!license.boundIp().equals(requestIp)) {
                return VerificationResult.failure("IP_MISMATCH", "卡密已绑定到其他服务器 IP");
            } else {
                repository.touchLicense(license.id(), now);
            }

            String message = license.customMessage() == null || license.customMessage().isBlank()
                    ? plugin.verificationMessage() : license.customMessage();
            return new VerificationResult(true, "VALID", message, license.expiresAt(), plugin.slug());
        });
    }

    private void insertUniqueKey(UUID pluginId, OffsetDateTime expiresAt, String customMessage,
                                 OffsetDateTime now, List<String> result) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String key = generateKey();
            License license = new License(UUID.randomUUID(), pluginId, hasher.hash(key), key.substring(0, 8),
                    "ACTIVE", expiresAt, null, normalizeMessage(customMessage), now, null, null, null);
            try {
                repository.insertLicense(license);
                result.add(key);
                return;
            } catch (DuplicateKeyException ignored) {
                // A cryptographically improbable collision is retried with a fresh key.
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "KEY_GENERATION_FAILED", "卡密生成失败，请重试");
    }

    private String generateKey() {
        StringBuilder key = new StringBuilder("CLD");
        for (int group = 0; group < 4; group++) {
            key.append('-');
            for (int index = 0; index < 5; index++) {
                key.append(KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)]);
            }
        }
        return key.toString();
    }

    private String normalizeMessage(String message) {
        return message == null || message.isBlank() ? null : message.trim();
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public record GeneratedBatch(String plugin, List<String> keys, OffsetDateTime expiresAt) {
    }

    public record VerificationResult(
            boolean valid,
            String status,
            String message,
            OffsetDateTime expiresAt,
            String plugin
    ) {
        static VerificationResult failure(String status, String message) {
            return new VerificationResult(false, status, message, null, null);
        }
    }
}
