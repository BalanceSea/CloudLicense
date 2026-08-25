package cn.cloudlicense.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PluginVersion(
        UUID id,
        UUID pluginId,
        String version,
        String fileName,
        String storedPath,
        String sha256,
        long sizeBytes,
        String changelog,
        boolean isPublic,
        OffsetDateTime createdAt
) {
}

