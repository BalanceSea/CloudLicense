package cn.cloudlicense.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.cloudlicense.service.RequestIpResolver;
import cn.cloudlicense.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CloudLicenseProperties properties,
                                            ObjectMapper objectMapper, UserService userService,
                                            RequestIpResolver requestIpResolver) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new AdminKeyFilter(properties.adminKey(), objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new VerifyRateLimitFilter(properties.verifyRateLimitPerMinute(), objectMapper,
                                requestIpResolver),
                        AdminKeyFilter.class)
                .addFilterAfter(new AuthRateLimitFilter(objectMapper, requestIpResolver), VerifyRateLimitFilter.class)
                .addFilterAfter(new UserSessionFilter(userService, objectMapper), AuthRateLimitFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CloudLicenseProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
