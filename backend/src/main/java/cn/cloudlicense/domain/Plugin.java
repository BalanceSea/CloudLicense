package cn.cloudlicense.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Plugin(
        UUID id,
        String slug,
        String name,
        String description,
        String verificationMessage,
        OffsetDateTime createdAt
) {
}

