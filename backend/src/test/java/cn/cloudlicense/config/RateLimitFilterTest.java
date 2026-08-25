package cn.cloudlicense.config;

import cn.cloudlicense.service.RequestIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequestIpResolver requestIpResolver = new RequestIpResolver(new CloudLicenseProperties(
            "admin", "pepper", Path.of("storage"), "", true, 120, List.of("http://localhost")));

    @Test
    void authLimitSeparatesClientsBehindTrustedProxy() throws Exception {
        Filter filter = new AuthRateLimitFilter(objectMapper, requestIpResolver);

        for (int index = 1; index <= 21; index++) {
            MockHttpServletResponse response = execute(filter, "/api/v1/users/login", index);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void verifyLimitSeparatesClientsBehindTrustedProxy() throws Exception {
        Filter filter = new VerifyRateLimitFilter(1, objectMapper, requestIpResolver);

        assertEquals(200, execute(filter, "/api/v1/licenses/verify", 1).getStatus());
        assertEquals(200, execute(filter, "/api/v1/licenses/verify", 2).getStatus());
    }

    private MockHttpServletResponse execute(Filter filter, String uri, int clientSuffix) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113." + clientSuffix);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
