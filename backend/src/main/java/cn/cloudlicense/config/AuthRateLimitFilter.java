package cn.cloudlicense.config;

import cn.cloudlicense.service.RequestIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT_PER_MINUTE = 20;
    private final ObjectMapper objectMapper;
    private final RequestIpResolver requestIpResolver;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(ObjectMapper objectMapper, RequestIpResolver requestIpResolver) {
        this.objectMapper = objectMapper;
        this.requestIpResolver = requestIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !("/api/v1/users/login".equals(uri) || "/api/v1/users/register".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.compute(requestIpResolver.resolve(request), (ip, previous) ->
                previous == null || previous.minute != minute ? new Window(minute) : previous);
        if (window.requests.incrementAndGet() > LIMIT_PER_MINUTE) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "code", "RATE_LIMITED", "message", "登录请求过于频繁，请稍后再试"));
            return;
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute < minute - 2);
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger requests = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
