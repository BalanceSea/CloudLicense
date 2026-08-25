package cn.cloudlicense;

import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.domain.PluginVersion;
import cn.cloudlicense.repository.CloudLicenseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserApiIntegrationTest {
    private static final String ADMIN_KEY = "integration-admin-key";
    private static final String PASSWORD = "correct-horse-42";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CloudLicenseRepository repository;

    private final List<Path> createdFiles = new ArrayList<>();

    @AfterEach
    void removeCreatedFiles() throws Exception {
        for (Path file : createdFiles) {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void userEndpointsRequireSession() throws Exception {
        mockMvc.perform(get("/api/v1/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void registrationRejectsDuplicateUsernameAndLoginUsesGenericFailure() throws Exception {
        String username = uniqueUsername("account");
        register(username);

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));

        mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "incorrect-42")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void claimedLicenseIsExclusiveAndOnlyOwnerCanUnbind() throws Exception {
        String ownerToken = register(uniqueUsername("owner"));
        String otherToken = register(uniqueUsername("other"));
        Plugin plugin = repository.findPlugin("cloudfashion").orElseThrow();
        String key = generateKey(plugin);

        mockMvc.perform(get("/api/v1/user/plugins").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String claimedBody = mockMvc.perform(post("/api/v1/user/licenses/claim")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claim("cloudfashion", key)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID licenseId = UUID.fromString(objectMapper.readTree(claimedBody).path("id").asText());

        mockMvc.perform(post("/api/v1/user/licenses/claim")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claim("cloudfashion", key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LICENSE_ALREADY_CLAIMED"));

        mockMvc.perform(get("/api/v1/user/plugins").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("cloudfashion"))
                .andExpect(jsonPath("$[0].licenses[0].id").value(licenseId.toString()));

        mockMvc.perform(post("/api/v1/licenses/verify")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.41");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claim("cloudfashion", key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/v1/user/licenses/{id}/unbind", licenseId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/user/licenses/{id}/unbind", licenseId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/licenses/verify")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.42");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claim("cloudfashion", key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void downloadRequiresOwnedActiveLicenseAndAnonymousRouteIsGone() throws Exception {
        String ownerToken = register(uniqueUsername("download"));
        String otherToken = register(uniqueUsername("viewer"));
        Plugin plugin = repository.findPlugin("cloudchest").orElseThrow();
        String version = "test-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] jarBytes = "integration-test-jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = Path.of("target", "test-storage", "cloudchest", version, "cloudchest-test.jar")
                .toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Files.write(file, jarBytes);
        createdFiles.add(file);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        repository.insertVersion(new PluginVersion(UUID.randomUUID(), plugin.id(), version, file.getFileName().toString(),
                file.toString(), checksum, jarBytes.length, "test release", true, OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/user/plugins/cloudchest/download")
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLUGIN_NOT_OWNED"));

        String key = generateKey(plugin);
        mockMvc.perform(post("/api/v1/user/licenses/claim")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claim("cloudchest", key)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/user/plugins/cloudchest/download")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Checksum-SHA256", checksum))
                .andExpect(content().bytes(jarBytes));

        mockMvc.perform(get("/api/v1/public/plugins/cloudchest/latest/download"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    void ownedPluginWithoutPublishedVersionReturnsVersionError() throws Exception {
        String token = register(uniqueUsername("noversion"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Plugin plugin = repository.createPlugin("empty-" + suffix, "Empty " + suffix, "", "授权通过");
        String key = generateKey(plugin);

        mockMvc.perform(post("/api/v1/user/licenses/claim")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claim(plugin.slug(), key)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/user/plugins/{slug}/download", plugin.slug())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        String token = register(uniqueUsername("logout"));
        mockMvc.perform(post("/api/v1/user/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    private String register(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value(username))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }

    private String generateKey(Plugin plugin) throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/plugins/{id}/licenses", plugin.id())
                        .header("Authorization", bearer(ADMIN_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"durationDays\":30,\"customMessage\":\"\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("keys").get(0).asText();
    }

    private String credentials(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new Credentials(username, password));
    }

    private String claim(String plugin, String key) throws Exception {
        return objectMapper.writeValueAsString(new Claim(plugin, key));
    }

    private String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Credentials(String username, String password) {
    }

    private record Claim(String plugin, String licenseKey) {
    }
}
