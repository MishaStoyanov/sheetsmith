package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.auth.AccessTokenService;
import com.ap0stole.sheetsmith.auth.JwtAuthenticationFilter;
import com.ap0stole.sheetsmith.auth.JwtSecretProvider;
import com.ap0stole.sheetsmith.domain.entity.AuthSecret;
import com.ap0stole.sheetsmith.repository.AuthSecretRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The token filter and what it needs, for slice tests that only care about the chain around it.
 * <p>
 * The filter is the real one rather than a mock on purpose: a mocked {@code doFilter} does nothing,
 * which stops the chain dead and fails every request in the slice for a reason that has nothing to
 * do with what is under test. Its dependencies are cheap, so the honest version costs less than the
 * fake one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthTestBeans {

    @Bean
    AuthSecretRepository authSecretRepository() {
        Map<String, AuthSecret> secrets = new HashMap<>();
        AuthSecretRepository repository = mock(AuthSecretRepository.class);
        when(repository.save(any())).thenAnswer(call -> {
            AuthSecret secret = call.getArgument(0);
            secrets.put(secret.getName(), secret);
            return secret;
        });
        when(repository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(secrets.get(call.<String>getArgument(0))));
        return repository;
    }

    @Bean
    JwtSecretProvider jwtSecretProvider(AuthConfig authConfig, AuthSecretRepository secrets) {
        return new JwtSecretProvider(authConfig, secrets);
    }

    @Bean
    AccessTokenService accessTokenService(AuthConfig authConfig, JwtSecretProvider secretProvider) {
        return new AccessTokenService(authConfig, secretProvider);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(AuthConfig authConfig, AccessTokenService accessTokens) {
        return new JwtAuthenticationFilter(authConfig, accessTokens);
    }
}
