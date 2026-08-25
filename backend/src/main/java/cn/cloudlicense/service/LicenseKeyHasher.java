package cn.cloudlicense.service;

import cn.cloudlicense.config.CloudLicenseProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
public class LicenseKeyHasher {
    private final byte[] pepper;

    public LicenseKeyHasher(CloudLicenseProperties properties) {
        this.pepper = properties.licensePepper().getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalize(key).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    public String normalize(String key) {
        return key == null ? "" : key.trim().toUpperCase();
    }
}

