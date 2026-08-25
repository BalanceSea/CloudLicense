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

public class VerifyRateLimitFilter extends OncePerRequestFilter {
    private final int limit;
    private final ObjectMapper objectMapper;
    private final RequestIpResolver requestIpResolver;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public VerifyRateLimitFilter(int limit, ObjectMapper objectMapper, RequestIpResolver requestIpResolver) {
        this.limit = Math.max(1, limit);
        this.objectMapper = objectMapper;
        this.requestIpResolver = requestIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/v1/licenses/verify".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.compute(requestIpResolver.resolve(request), (ip, previous) ->
                previous == null || previous.minute != minute ? new Window(minute) : previous);
        if (window.requests.incrementAndGet() > limit) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "code", "RATE_LIMITED",
                    "message", "验证请求过于频繁，请稍后再试"
            ));
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
