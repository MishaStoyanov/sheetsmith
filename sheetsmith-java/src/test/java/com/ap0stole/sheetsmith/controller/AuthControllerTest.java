package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.auth.AccessTokenService;
import com.ap0stole.sheetsmith.auth.AuthService;
import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.auth.JwtAuthenticationFilter;
import com.ap0stole.sheetsmith.auth.JwtSecretProvider;
import com.ap0stole.sheetsmith.auth.RefreshTokenService;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.configs.SecurityConfig;
import com.ap0stole.sheetsmith.configs.SecurityProperties;
import com.ap0stole.sheetsmith.domain.entity.AuthSecret;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.repository.AuthSecretRepository;
import com.ap0stole.sheetsmith.repository.RefreshTokenRepository;
import com.ap0stole.sheetsmith.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-in round trip over real HTTP, because the parts that go wrong here are not in the
 * services: which credential travels in which channel, and whether the cookie comes back with
 * attributes that make it safe and make it work.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({AuthConfig.class, SecurityProperties.class, SecurityConfig.class, ChatConfig.class,
        AuthService.class, AccessTokenService.class, RefreshTokenService.class,
        JwtSecretProvider.class, JwtAuthenticationFilter.class, CurrentUser.class,
        AuthControllerTest.Beans.class})
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
class AuthControllerTest {

    private static final String GOOD = """
            {"name":"dana","password":"correct-horse","rememberMe":false}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @BeforeEach
    void seedUser() {
        User dana = User.of("dana", new BCryptPasswordEncoder().encode("correct-horse"));
        dana.setId(1L);
        when(users.findByName("dana")).thenReturn(Optional.of(dana));
        when(users.findByName("nobody")).thenReturn(Optional.empty());
        when(users.findById(1L)).thenReturn(Optional.of(dana));
    }

    @Test
    @DisplayName("signing in returns an access token in the body and the refresh token in a cookie")
    void theTwoCredentialsTravelSeparately() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(GOOD))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("user").get("name").asText()).isEqualTo("dana");
        assertThat(body.toString())
                .as("the thirty-day credential must never be readable by a script on the page")
                .doesNotContain("refresh");

        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("sheetsmith_refresh=");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Strict");
        assertThat(cookie)
                .as("scoped to the auth path: no other endpoint has any use for it")
                .contains("Path=/api/auth");
    }

    @Test
    @DisplayName("remember me is the difference between a day and a month, on the cookie itself")
    void rememberMeLengthensTheSession() throws Exception {
        // The flag travels from a checkbox through two services to a Max-Age, and every step of
        // that was tested except the one the browser actually obeys.
        int aDay = maxAgeAfterLogin(false);
        int aMonth = maxAgeAfterLogin(true);

        assertThat(aDay).isCloseTo((int) Duration.ofDays(1).toSeconds(), within(120));
        assertThat(aMonth).isCloseTo((int) Duration.ofDays(30).toSeconds(), within(120));
    }

    private int maxAgeAfterLogin(boolean rememberMe) throws Exception {
        String body = """
                {"name":"dana","password":"correct-horse","rememberMe":%s}""".formatted(rememberMe);

        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getCookie("sheetsmith_refresh")
                .getMaxAge();
    }

    @Test
    @DisplayName("a wrong password and an unknown name are refused in the same words")
    void failuresAreIndistinguishable() throws Exception {
        // Telling the two apart turns the login form into a way of asking which usernames exist.
        String wrongPassword = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"dana","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nobody","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(wrongPassword).get("message").asText())
                .isEqualTo(objectMapper.readTree(unknownUser).get("message").asText());
    }

    @Test
    @DisplayName("the access token from a sign-in opens the API")
    void theTokenWorks() throws Exception {
        String token = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON).content(GOOD))
                        .andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("without a token /me is 401, so the browser knows to refresh rather than give up")
    void meIsUnauthorizedWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refreshing exchanges the cookie for a new one")
    void refreshRotatesTheCookie() throws Exception {
        MvcResult signIn = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(GOOD))
                .andReturn();
        Cookie issued = signIn.getResponse().getCookie("sheetsmith_refresh");

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(issued))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(refreshed.getResponse().getCookie("sheetsmith_refresh").getValue())
                .isNotEqualTo(issued.getValue());

        // The old cookie is spent. A browser that kept it must not be able to spend it twice.
        mockMvc.perform(post("/api/auth/refresh").cookie(issued))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refreshing with no cookie at all is 401, not a server error")
    void refreshWithoutACookieIsRefusedCleanly() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("signing out clears the cookie and the token stops working")
    void signOutEndsTheSession() throws Exception {
        Cookie issued = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(GOOD))
                .andReturn().getResponse().getCookie("sheetsmith_refresh");

        MvcResult signOut = mockMvc.perform(post("/api/auth/logout").cookie(issued))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(signOut.getResponse().getCookie("sheetsmith_refresh").getMaxAge())
                .as("cleared with the attributes it was set with, or the browser keeps the old one")
                .isZero();
        mockMvc.perform(post("/api/auth/refresh").cookie(issued)).andExpect(status().isUnauthorized());
    }

    /**
     * In-memory stand-ins for the two repositories. The refresh store has to behave like a real one
     * — the replay case turns entirely on a row being findable after it was marked used.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

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
        RefreshTokenRepository refreshTokenRepository() {
            List<com.ap0stole.sheetsmith.domain.entity.RefreshToken> stored = new ArrayList<>();
            AtomicLong ids = new AtomicLong();
            RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
            when(repository.save(any())).thenAnswer(call -> {
                com.ap0stole.sheetsmith.domain.entity.RefreshToken token = call.getArgument(0);
                if (token.getId() == null) {
                    token.setId(ids.incrementAndGet());
                    stored.add(token);
                }
                return token;
            });
            when(repository.findByTokenHash(anyString())).thenAnswer(call -> {
                String hash = call.getArgument(0);
                return stored.stream().filter(token -> token.getTokenHash().equals(hash)).findFirst();
            });
            when(repository.revokeAllForUser(any(), any())).thenAnswer(call -> {
                int count = 0;
                for (com.ap0stole.sheetsmith.domain.entity.RefreshToken token : stored) {
                    if (token.getUser().getId().equals(call.getArgument(0))
                            && token.getRevokedAt() == null && token.getUsedAt() == null) {
                        token.setRevokedAt(call.getArgument(1));
                        count++;
                    }
                }
                return count;
            });
            return repository;
        }
    }
}
