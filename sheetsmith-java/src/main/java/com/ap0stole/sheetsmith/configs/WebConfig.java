package com.ap0stole.sheetsmith.configs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final SecurityConfig securityConfig;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Logged so an intranet deployment can see at a glance why its browser is being refused.
        log.info("CORS: /api/** accepts origins {}; override with XLSXAI_ALLOWED_ORIGINS (comma-separated)",
                securityConfig.getAllowedOrigins());

        registry.addMapping("/api/**")
                .allowedOrigins(securityConfig.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE", "PUT")
                .allowedHeaders("*");
    }
}
