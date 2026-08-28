package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The filter chain, in the two shapes this app has.
 * <p>
 * With {@link AuthConfig} off — the default — the chain permits everything, which is the app as it
 * has always behaved. With it on, {@code /api/**} needs a token and only a handful of paths stay
 * open. Both shapes are built here rather than one being a set of guards inside the other, because
 * "which requests are allowed through" should be readable in one place.
 */
@Slf4j
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Open in both shapes: the UI itself, the question "what can this instance do", health, and the
     * API's own description.
     * <p>
     * The OpenAPI document lists paths and shapes, never data — and this is an open-source
     * application whose endpoints are in its README anyway. Hiding it behind the login would buy
     * nothing and cost the one page that makes the API usable from outside a browser.
     */
    private static final String[] ALWAYS_OPEN = {
            "/", "/index.html", "/assets/**", "/favicon.ico", "/vite.svg",
            "/api/capabilities", "/actuator/health",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    /**
     * The three ways in. They must stay open or there is no way to become authenticated: signing in
     * has no token yet, and refreshing runs precisely because the access token has expired.
     */
    private static final String[] AUTH_ENTRY_POINTS = {
            "/api/auth/login", "/api/auth/refresh", "/api/auth/logout"
    };

    private final AuthConfig authConfig;
    private final SecurityProperties securityProperties;
    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // No cookies carry authority here — the token does — so there is no session for a
                // cross-site form post to ride on, and CSRF protection would only break the API.
                // The refresh cookie that arrives in a later commit is scoped SameSite=Strict to
                // one path for that reason.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The browser's own auth dialog is worse than useless for an API: it appears over
                // the app and cannot be dismissed into anything sensible.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        if (!authConfig.isEnabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        // 401, not Spring's default 403 for an anonymous caller. The difference is the whole
        // silent-refresh design: the browser retries once on 401 with a fresh access token, and a
        // 403 means "you are known and still may not", which is not something a retry can fix.
        http.exceptionHandling(handling ->
                handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        http.authorizeHttpRequests(auth -> auth
                // Preflight carries no credentials by definition, so refusing it would fail every
                // cross-origin call before the real request was ever made.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(ALWAYS_OPEN).permitAll()
                .requestMatchers(AUTH_ENTRY_POINTS).permitAll()

                // Everything the application actually serves, and nothing else. Who may call each
                // one is written on the handler itself — @PreAuthorize, beside the mapping, where
                // somebody reading the endpoint can see the rule that governs it.
                .requestMatchers("/api/auth/me").authenticated()
                .requestMatchers("/api/users/**").authenticated()
                .requestMatchers("/api/prices/**").authenticated()
                .requestMatchers("/api/settings/**").authenticated()
                .requestMatchers("/api/excel/**").authenticated()
                .requestMatchers("/api/chat/**").authenticated()
                .requestMatchers("/api/history/**").authenticated()
                .requestMatchers("/api/analytics/**").authenticated()
                .requestMatchers("/api/prompts/**").authenticated()

                // The line the whole file exists for. It used to read "/api/** → authenticated",
                // which meant an endpoint nobody had remembered to guard was open to any signed-in
                // person — and five of them were. A path that is not named here answers 403 the
                // first time it is called: a bug found in the first minute rather than a hole found
                // by somebody else. The other half of that promise is EndpointGuardTest, which
                // refuses to let a handler exist without a rule on it.
                .requestMatchers("/api/**").denyAll()
                .anyRequest().permitAll());

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * One source of truth for CORS, read by the chain rather than registered with MVC.
     * <p>
     * The allowlist is not decoration: with no authentication, {@code *} would let any site the
     * user happens to have open drive their instance — including overwriting the stored cloud API
     * keys through {@code PUT /api/settings}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        log.info("CORS: /api/** accepts origins {}; override with SHEETSMITH_ALLOWED_ORIGINS (comma-separated)",
                securityProperties.getAllowedOrigins());

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(securityProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Named origins, not a wildcard, so the browser is allowed to send the refresh cookie.
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** bcrypt: the hash a password column is named for, and the only thing ever written to it. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
