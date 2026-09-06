package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which passes through the chain the token filter takes part in, and whether the caller survives
 * them.
 */
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "a-token";

    private final AuthConfig authConfig = new AuthConfig();
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(authConfig, accessTokens);

    @BeforeEach
    void signTokenIn() {
        authConfig.setEnabled(true);
        when(accessTokens.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN)
                .header("alg", "HS256")
                .subject("1")
                .claim("name", "admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the caller is read from the token on an ordinary request")
    void authenticatesARequest() throws Exception {
        filter.doFilter(request(DispatcherType.REQUEST), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    /**
     * The pass that carries a streamed answer back, and the one this filter used to sit out.
     * <p>
     * A chat turn returns an {@code SseEmitter} and finishes on another thread; completing it makes
     * Spring MVC send the request through the chain a second time. {@code OncePerRequestFilter}
     * skips async dispatches unless told otherwise, and the chain is stateless — so there was no
     * context to restore and nothing to read the token, the caller arrived anonymous, and
     * {@code AuthorizationFilter} refused {@code /api/chat/**} after the response had been
     * committed. The refusal could not even be sent: the turn had run, the answer was written to
     * the database, and the browser was left holding a stream that simply stopped.
     */
    @Test
    @DisplayName("and again on the async dispatch, or a streamed answer never reaches the browser")
    void authenticatesAnAsyncDispatchToo() throws Exception {
        filter.doFilter(request(DispatcherType.ASYNC), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the token must be read again on the dispatch that carries the answer")
                .isNotNull();
    }

    @Test
    @DisplayName("absent entirely where nobody signs in")
    void stepsAsideWithAuthenticationOff() throws Exception {
        authConfig.setEnabled(false);

        filter.doFilter(request(DispatcherType.REQUEST), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest request(DispatcherType dispatch) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(dispatch);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }
}
