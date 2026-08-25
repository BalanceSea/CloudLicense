package cn.cloudlicense.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record License(
        UUID id,
        UUID pluginId,
        String keyHash,
        String keyPrefix,
        String status,
        OffsetDateTime expiresAt,
        String boundIp,
        String customMessage,
        OffsetDateTime createdAt,
        OffsetDateTime boundAt,
        OffsetDateTime lastVerifiedAt,
        UUID userId
) {
}
