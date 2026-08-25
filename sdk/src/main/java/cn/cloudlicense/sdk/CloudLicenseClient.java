package cn.cloudlicense.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class CloudLicenseClient {
    private final URI baseUri;
    private final String plugin;
    private final String licenseKey;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper json;

    private CloudLicenseClient(Builder builder) {
        this.baseUri = normalizeBaseUri(builder.baseUri);
        this.plugin = requireText(builder.plugin, "plugin").toLowerCase();
        this.licenseKey = requireText(builder.licenseKey, "licenseKey");
        this.requestTimeout = builder.requestTimeout;
        this.httpClient = builder.httpClient != null ? builder.httpClient : HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public static Builder builder() {
        return new Builder();
    }

    public VerificationResult verify() {
        try {
            HttpResponse<String> response = httpClient.send(verificationRequest(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseVerification(response);
        } catch (IOException exception) {
            return VerificationResult.failure("NETWORK_ERROR", "无法连接授权服务器");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return VerificationResult.failure("INTERRUPTED", "授权验证被中断");
        }
    }

    public CompletableFuture<VerificationResult> verifyAsync() {
        return httpClient.sendAsync(verificationRequest(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parseVerification)
                .exceptionally(error -> VerificationResult.failure("NETWORK_ERROR", "无法连接授权服务器"));
    }

    public LatestVersion latestVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint("api/v1/public/plugins/" + plugin + "/latest"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Latest version request failed with HTTP " + response.statusCode());
        }
        return json.readValue(response.body(), LatestVersion.class);
    }

    public UpdateResult checkForUpdate(String currentVersion) {
        try {
            LatestVersion latest = latestVersion();
            boolean updateAvailable = SemanticVersions.compare(latest.version(), currentVersion) > 0;
            return new UpdateResult(true, updateAvailable, currentVersion, latest, "OK");
        } catch (IOException exception) {
            return new UpdateResult(false, false, currentVersion, null, "VERSION_CHECK_FAILED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new UpdateResult(false, false, currentVersion, null, "INTERRUPTED");
        }
    }

    public URI latestDownloadUri() {
        return endpoint("api/v1/public/plugins/" + plugin + "/latest/download");
    }

    private HttpRequest verificationRequest() {
        String body;
        try {
            body = json.writeValueAsString(new VerifyRequest(plugin, licenseKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode license request", exception);
        }
        return HttpRequest.newBuilder(endpoint("api/v1/licenses/verify"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private VerificationResult parseVerification(HttpResponse<String> response) {
        try {
            if (response.statusCode() == 200) {
                return json.readValue(response.body(), VerificationResult.class);
            }
            ApiError error = json.readValue(response.body(), ApiError.class);
            return VerificationResult.failure(error.code(), error.message());
        } catch (IOException exception) {
            return VerificationResult.failure("INVALID_RESPONSE", "授权服务器返回了无效响应");
        }
    }

    private URI endpoint(String path) {
        return baseUri.resolve(path);
    }

    private static URI normalizeBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        String raw = value.toString();
        return URI.create(raw.endsWith("/") ? raw : raw + "/");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private URI baseUri;
        private String plugin;
        private String licenseKey;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private HttpClient httpClient;

        private Builder() {
        }

        public Builder baseUri(String baseUri) {
            this.baseUri = URI.create(baseUri);
            return this;
        }

        public Builder plugin(String plugin) {
            this.plugin = plugin;
            return this;
        }

        public Builder licenseKey(String licenseKey) {
            this.licenseKey = licenseKey;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout);
            return this;
        }

        Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public CloudLicenseClient build() {
            return new CloudLicenseClient(this);
        }
    }

    private record VerifyRequest(String plugin, String licenseKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ApiError(String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LatestVersion(
            String plugin,
            String version,
            String sha256,
            long sizeBytes,
            String changelog,
            OffsetDateTime publishedAt,
            String downloadUrl
    ) {
    }

    public record UpdateResult(
            boolean checked,
            boolean updateAvailable,
            String currentVersion,
            LatestVersion latest,
            String status
    ) {
    }
}

