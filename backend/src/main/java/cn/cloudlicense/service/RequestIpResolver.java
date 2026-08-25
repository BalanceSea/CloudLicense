package cn.cloudlicense.service;

import cn.cloudlicense.config.CloudLicenseProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestIpResolver {
    private final boolean trustForwardedFor;

    public RequestIpResolver(CloudLicenseProperties properties) {
        this.trustForwardedFor = properties.trustForwardedFor();
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                String candidate = forwarded.split(",", 2)[0].trim();
                if (candidate.length() <= 45 && candidate.matches("[0-9a-fA-F:.]+")) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}

