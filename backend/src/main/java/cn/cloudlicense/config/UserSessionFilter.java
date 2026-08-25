package cn.cloudlicense.config;

import cn.cloudlicense.domain.UserAccount;
import cn.cloudlicense.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;

public class UserSessionFilter extends OncePerRequestFilter {
    public static final String USER_ATTRIBUTE = UserSessionFilter.class.getName() + ".user";
    public static final String TOKEN_ATTRIBUTE = UserSessionFilter.class.getName() + ".token";

    private final UserService users;
    private final ObjectMapper objectMapper;

    public UserSessionFilter(UserService users, ObjectMapper objectMapper) {
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/user/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        UserAccount user = users.authenticate(token);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "code", "UNAUTHORIZED",
                    "message", "用户登录已失效，请重新登录",
                    "timestamp", OffsetDateTime.now().toString()
            ));
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, user);
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        chain.doFilter(request, response);
    }
}
