package cn.cloudlicense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "cloudlicense")
public record CloudLicenseProperties(
        String adminKey,
        String licensePepper,
        Path storageRoot,
        String nativeLibrary,
        boolean trustForwardedFor,
        int verifyRateLimitPerMinute,
        List<String> allowedOrigins
) {
}
