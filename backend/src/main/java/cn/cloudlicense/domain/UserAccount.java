package cn.cloudlicense.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String passwordHash,
        String status,
        OffsetDateTime createdAt
) {
}
