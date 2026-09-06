package com.dealflow360.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless HTTP Basic security: the frontend sends an Authorization
 * header with every fetch() call after a successful login check against
 * /api/auth/me. No sessions, no CSRF tokens - simple and easy to reason
 * about for a hackathon build. Fine-grained per-action rules (e.g. only
 * Sales Manager/Finance can approve) are enforced with @PreAuthorize on
 * the service/controller methods themselves (see @EnableMethodSecurity).
 * <p>
 * Bug fix: the default Basic entry point answers a failed login with a
 * {@code WWW-Authenticate: Basic} header, which makes the browser pop up its own
 * native username/password dialog on top of the app's login form every time a
 * password is mistyped (and again whenever a stale session hits a 401). Both
 * the 401 and the 403 now come back as plain JSON with a message, no challenge
 * header, so the app's own error box is the only thing the user sees.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(CustomUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    public AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized", "Invalid username or password, or your session has expired - please sign in again");
    }

    @Bean
    public AccessDeniedHandler jsonAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                "Forbidden", "Your role is not allowed to perform this action");
    }

    private static void writeJson(HttpServletResponse response, int status, String error, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthenticationEntryPoint entryPoint, AccessDeniedHandler deniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Static frontend (plain HTML/CSS/JS) - always visible, login happens client-side against /api/auth/me
                        .requestMatchers("/", "/*.html", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        // PDF A1 - "Internal users can sign up": self-registration is the one API call made before a login exists.
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll()
                        // Everything under /api/** requires a valid login; fine-grained role checks live on the methods.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint)) // browser/JS sends "Authorization: Basic base64(user:pass)"
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .formLogin(form -> form.disable());
        return http.build();
    }
}
