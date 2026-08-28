package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.auth.Authz;
import com.ap0stole.sheetsmith.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.function.BooleanSupplier;

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

    /** Open in both shapes: the UI itself, the question "what can this instance do", and health. */
    private static final String[] ALWAYS_OPEN = {
            "/", "/index.html", "/assets/**", "/favicon.ico", "/vite.svg",
            "/api/capabilities", "/actuator/health"
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
    private final Authz authz;

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

        AuthorizationManager<RequestAuthorizationContext> admin = rule(authz::admin);
        AuthorizationManager<RequestAuthorizationContext> superadmin = rule(authz::superadmin);

        http.authorizeHttpRequests(auth -> auth
                // Preflight carries no credentials by definition, so refusing it would fail every
                // cross-origin call before the real request was ever made.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(ALWAYS_OPEN).permitAll()
                .requestMatchers(AUTH_ENTRY_POINTS).permitAll()

                // ── The machine's own configuration, and anything that removes work ────────────
                // Where the files live, which model is called and with whose key, what a token
                // costs: none of it is anybody's data, all of it decides what the instance does
                // with everybody's. Deletion sits here for the reason it always has — it is the
                // one act no other administrator can undo.
                .requestMatchers("/api/settings/**").access(superadmin)
                .requestMatchers(HttpMethod.PUT, "/api/prices").access(superadmin)
                .requestMatchers(HttpMethod.PATCH, "/api/prices/*").access(superadmin)
                .requestMatchers(HttpMethod.DELETE, "/api/prices/*").access(superadmin)
                .requestMatchers(HttpMethod.POST, "/api/prices/catalogue/**").access(superadmin)
                .requestMatchers(HttpMethod.DELETE, "/api/users/*").access(superadmin)
                .requestMatchers(HttpMethod.DELETE, "/api/history/*").access(superadmin)
                .requestMatchers(HttpMethod.DELETE, "/api/chat/sessions/*").access(superadmin)

                // ── Managing accounts ─────────────────────────────────────────────────────────
                // Listed before the patterns below them, because "budget-requests" and "me" are
                // both one path segment and would otherwise be read as somebody's id.
                .requestMatchers(HttpMethod.GET, "/api/users/budget-requests").access(admin)
                .requestMatchers(HttpMethod.POST, "/api/users/budget-requests/*/decide").access(admin)
                .requestMatchers(HttpMethod.PATCH, "/api/users/*/role").access(admin)
                .requestMatchers(HttpMethod.PUT, "/api/users/*/budget").access(admin)
                .requestMatchers(HttpMethod.POST, "/api/users").access(admin)
                .requestMatchers(HttpMethod.PUT, "/api/users/*").access(admin)

                // ── Signed in, and the rule that remains depends on the row ───────────────────
                // These cannot be decided from the path: whose run this is, whose document, whose
                // account. The chain gets them as far as "you are somebody"; the service decides
                // which somebody, because only it has the row in hand.
                .requestMatchers("/api/auth/me").authenticated()
                .requestMatchers("/api/users/me/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/users/search").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/users/*").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/prices/search").authenticated()
                .requestMatchers("/api/excel/**").authenticated()
                .requestMatchers("/api/chat/**").authenticated()
                .requestMatchers("/api/history/**").authenticated()
                .requestMatchers("/api/analytics/**").authenticated()
                .requestMatchers("/api/prompts/**").authenticated()

                // ── Everything else under /api is refused ─────────────────────────────────────
                // The point of the list above is this line. It used to read "/api/** →
                // authenticated", which meant every endpoint nobody had remembered to guard was
                // open to any signed-in person — and five of them were. A path that is not named
                // here now answers 403 the first time it is called, which is a bug found in the
                // first minute rather than a hole found by somebody else.
                .requestMatchers("/api/**").denyAll()
                .anyRequest().permitAll());

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * A role rule as the chain can read it.
     * <p>
     * The role is not in the token — it is read from the database per request, so that a demotion
     * takes effect now rather than when the token expires — which is why these are calls into
     * {@link Authz} rather than {@code hasRole()}. The same bean answers the method annotations, so
     * a path rule and a method rule cannot drift into disagreeing.
     */
    private AuthorizationManager<RequestAuthorizationContext> rule(BooleanSupplier allowed) {
        return (authentication, context) -> new AuthorizationDecision(allowed.getAsBoolean());
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
