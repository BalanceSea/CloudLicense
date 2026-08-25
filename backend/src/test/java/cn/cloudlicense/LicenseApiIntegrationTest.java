package cn.cloudlicense;

import cn.cloudlicense.domain.Plugin;
import cn.cloudlicense.repository.CloudLicenseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LicenseApiIntegrationTest {
    private static final String ADMIN_KEY = "integration-admin-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CloudLicenseRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rejectsAdminRequestWithoutBearerKey() throws Exception {
        mockMvc.perform(get("/api/v1/admin/plugins"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void generatedLicenseBindsFirstIpAndRejectsDifferentIp() throws Exception {
        Plugin plugin = repository.findPlugin("cloudfashion").orElseThrow();
        String generated = mockMvc.perform(post("/api/v1/admin/plugins/{pluginId}/licenses", plugin.id())
                        .header("Authorization", "Bearer " + ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":1,"durationDays":30,"customMessage":"欢迎使用 CloudFashion"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(generated);
        String key = body.path("keys").get(0).asText();
        String request = objectMapper.writeValueAsString(new VerifyRequest("cloudfashion", key));

        mockMvc.perform(post("/api/v1/licenses/verify")
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr("203.0.113.10");
                            return servletRequest;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.message").value("欢迎使用 CloudFashion"));

        mockMvc.perform(post("/api/v1/licenses/verify")
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr("203.0.113.10");
                            return servletRequest;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/v1/licenses/verify")
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr("198.51.100.22");
                            return servletRequest;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("IP_MISMATCH"));
    }

    @Test
    void permanentLicenseHasNoExpiration() throws Exception {
        Plugin plugin = repository.findPlugin("cloudchest").orElseThrow();
        mockMvc.perform(post("/api/v1/admin/plugins/{pluginId}/licenses", plugin.id())
                        .header("Authorization", "Bearer " + ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"durationDays\":null,\"customMessage\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    private record VerifyRequest(String plugin, String licenseKey) {
    }
}

