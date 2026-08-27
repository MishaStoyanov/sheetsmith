package com.ap0stole.sheetsmith.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS deliberately does <em>not</em> live here any more.
 * <p>
 * It used to, as an MVC mapping — and an MVC mapping is applied by the dispatcher, which the
 * security filter chain runs in front of. With a filter chain present, a preflight {@code OPTIONS}
 * carries no credentials, so the chain would answer it 401 before MVC ever saw the mapping, and the
 * browser would report a CORS failure for a rule that was configured correctly. One
 * {@code CorsConfigurationSource} bean, read by the chain, is now the single answer — see
 * {@link SecurityConfig}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
