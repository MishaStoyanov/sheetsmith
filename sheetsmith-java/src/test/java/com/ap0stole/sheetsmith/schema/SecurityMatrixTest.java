package com.ap0stole.sheetsmith.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Who may call what, asked over HTTP with real tokens.
 *
 * <p>This file exists because of what it found. The rules were written on service methods, one
 * annotation at a time, and the filter chain asked for nothing beyond "you are signed in" — so an
 * endpoint nobody had remembered to annotate was open to every account on the instance. Five were:
 * the stored cloud API keys could be read and rewritten by anyone, so could the price table that
 * decides what everybody's work cost, an administrator could reset the superadmin's password and
 * sign in as them, and any signed-in person holding a session id could read and edit somebody
 * else's document.
 *
 * <p>None of that was reachable from the service-level tests, which is the point: they call methods
 * with a role in the context, and a method nobody guards is a method they never think to ask about.
 * The question here is the one an attacker asks — <em>what does this URL do for me?</em> — so it is
 * asked through the chain, with a token, exactly as a browser would.
 *
 * <p>Read the assertions as a table: each case is one path, one method, one role, one status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class SecurityMatrixTest {

    private static final String PASSWORD = "matrix-pass-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private Long superId;
    private Long adminId;
    private Long userId;

    private String asSuper;
    private String asAdmin;
    private String asUser;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("delete from document_sessions");
        jdbc.update("delete from model_prices");
        jdbc.update("delete from llm_settings");
        jdbc.update("delete from users where name like 'matrix-%'");

        superId = jdbc.queryForObject("select min(id) from users", Long.class);
        jdbc.update("update users set role = 'SUPERADMIN' where id = ?", superId);
        // The seeded account's password is 'admin' from the migration, and one test resets it.
        jdbc.update("update users set password_hash = ? where id = ?",
                "$2a$10$VXN.dor9JKKtAZ7xjhernu5kcCmarsDg1L7s.yN5z37tUP/rmWMGu", superId);

        asSuper = signIn("admin", "admin");
        adminId = createUser("matrix-boss", "ADMIN");
        userId = createUser("matrix-dana", "USER");
        asAdmin = signIn("matrix-boss", PASSWORD);
        asUser = signIn("matrix-dana", PASSWORD);
    }

    @AfterEach
    void tidy() {
        jdbc.update("delete from document_sessions");
        jdbc.update("delete from model_prices");
        jdbc.update("delete from llm_settings");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users where name like 'matrix-%'");
    }

    // ── The instance's own configuration ──────────────────────────────────────

    @Test
    @DisplayName("the model settings are the superadmin's, in both directions")
    void llmSettingsAreSuperadminOnly() throws Exception {
        // Was: no annotation at all. Any signed-in person could read the stored API keys and
        // repoint the instance at a provider of their choosing.
        mvc.perform(get("/api/settings").header("Authorization", asUser)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings").header("Authorization", asAdmin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings").header("Authorization", asSuper)).andExpect(status().isOk());

        mvc.perform(put("/api/settings").header("Authorization", asUser)
                        .contentType(MediaType.APPLICATION_JSON).content(settingsBody("sk-stolen")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a stored key goes in and never comes back out")
    void apiKeysAreNeverEchoed() throws Exception {
        mvc.perform(put("/api/settings").header("Authorization", asSuper)
                        .contentType(MediaType.APPLICATION_JSON).content(settingsBody("sk-secret-value")))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/settings").header("Authorization", asSuper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("the key itself never travels back to a browser")
                .doesNotContain("sk-secret-value");
        JsonNode cloud = json.readTree(body).get("cloud");
        assertThat(cloud.get("apiKeys").isEmpty()).isTrue();
        assertThat(cloud.get("savedKeys").toString())
                .as("what a screen actually needs: which providers have one")
                .contains("OPENAI");

        // Saving again without naming the key keeps it: the screen never received it, so it cannot
        // send it back, and a save that dropped every untouched secret would be a trap.
        mvc.perform(put("/api/settings").header("Authorization", asSuper)
                        .contentType(MediaType.APPLICATION_JSON).content(settingsBody(null)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select settings_json from llm_settings", String.class))
                .contains("sk-secret-value");

        // Naming it and leaving it blank is the other instruction, and the only way to say it.
        mvc.perform(put("/api/settings").header("Authorization", asSuper)
                        .contentType(MediaType.APPLICATION_JSON).content(settingsBody("")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select settings_json from llm_settings", String.class))
                .as("cleared on purpose, which is a different act from not touching it")
                .doesNotContain("sk-secret-value");
    }

    @Test
    @DisplayName("storage is the superadmin's, as it has been since it was written")
    void storageIsSuperadminOnly() throws Exception {
        mvc.perform(get("/api/settings/storage").header("Authorization", asUser)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/storage").header("Authorization", asAdmin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/storage").header("Authorization", asSuper)).andExpect(status().isOk());
    }

    // ── Money ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("writing a price is as guarded as deleting one")
    void pricesAreSuperadminOnly() throws Exception {
        // Was: only DELETE was guarded. PUT and PATCH were open to anybody signed in — and a price
        // rewrites what every past call cost, on every chart and against every spend limit.
        String price = """
                {"provider":"OPENAI","model":"gpt-4o","inputPerMillion":2.00,"outputPerMillion":10.00}""";

        mvc.perform(put("/api/prices").header("Authorization", asUser)
                .contentType(MediaType.APPLICATION_JSON).content(price)).andExpect(status().isForbidden());
        mvc.perform(put("/api/prices").header("Authorization", asAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(price)).andExpect(status().isForbidden());
        mvc.perform(put("/api/prices").header("Authorization", asSuper)
                .contentType(MediaType.APPLICATION_JSON).content(price)).andExpect(status().isOk());

        Long id = jdbc.queryForObject("select id from model_prices where model = 'gpt-4o'", Long.class);
        mvc.perform(patch("/api/prices/" + id).header("Authorization", asUser)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inputPerMillion\":0.01}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/prices/" + id + "?confirm=true").header("Authorization", asAdmin))
                .andExpect(status().isForbidden());

        // Reading them is not the same act: the screen that lists prices is open to everyone.
        mvc.perform(post("/api/prices/search").header("Authorization", asUser)
                .contentType(MediaType.APPLICATION_JSON).content("{\"page\":0,\"size\":20}"))
                .andExpect(status().isOk());
    }

    // ── Accounts ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an administrator cannot reset the superadmin's password")
    void anAdminCannotTakeOverTheSeededAccount() throws Exception {
        // The escalation this file was written for. Administrators may not demote each other — that
        // is the one-way door the role design rests on — but the guard on the patch asked only
        // whether the caller was an administrator, never who they were pointing at. So the door had
        // a window: reset the superadmin's password, sign in as them, demote whoever you like.
        mvc.perform(patch("/api/users/" + superId).header("Authorization", asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"taken-over-1\"}"))
                .andExpect(status().isForbidden());

        assertThat(signInFails("admin", "taken-over-1"))
                .as("and the password really did not change")
                .isTrue();

        // An administrator still does what an administrator is for.
        mvc.perform(patch("/api/users/" + userId).header("Authorization", asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"matrix-dana-renamed\"}"))
                .andExpect(status().isOk());
        jdbc.update("update users set name = 'matrix-dana' where id = ?", userId);
    }

    @Test
    @DisplayName("one administrator cannot reset another's password either")
    void adminsCannotResetEachOther() throws Exception {
        Long peer = createUser("matrix-peer", "ADMIN");

        mvc.perform(patch("/api/users/" + peer).header("Authorization", asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"peer-taken-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("creating accounts is an administrator's, deleting one is not")
    void theAccountLadderHolds() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", asUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"matrix-nope\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/users/" + userId).header("Authorization", asAdmin))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/" + userId).header("Authorization", asSuper))
                .andExpect(status().isNoContent());
    }

    // ── Somebody else's document ──────────────────────────────────────────────

    @Test
    @DisplayName("a session id in a colleague's hands opens nothing")
    void sessionsBelongToWhoeverOpenedThem() throws Exception {
        String sessionId = openSession(asUser);
        createUser("matrix-eve", "USER");
        String asPeer = signIn("matrix-eve", PASSWORD);

        // Was: every one of these served whoever asked. The id was the whole of the security.
        mvc.perform(get("/api/chat/sessions/" + sessionId).header("Authorization", asPeer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/sessions/" + sessionId + "/messages").header("Authorization", asPeer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/sessions/" + sessionId + "/file").header("Authorization", asPeer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/sessions/" + sessionId + "/revert").header("Authorization", asPeer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isNotFound());

        // Their own document is still theirs — and an administrator still sees an ordinary user's
        // work, by the same ladder the history has used since runs got owners. The boundary drawn
        // here is between peers, which is where it was missing entirely.
        mvc.perform(get("/api/chat/sessions/" + sessionId).header("Authorization", asUser))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/sessions/" + sessionId).header("Authorization", asAdmin))
                .andExpect(status().isOk());
    }

    // ── The default ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a path nobody named is refused rather than served")
    void unlistedApiPathsAreRefused() throws Exception {
        // The line that makes the rest of this file a rule instead of a list. Before it, anything
        // under /api that nobody had thought about answered to any signed-in caller.
        mvc.perform(get("/api/whatever-comes-next").header("Authorization", asUser))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/whatever-comes-next").header("Authorization", asSuper))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("without a token it is 401, so the browser knows to refresh")
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/history")).andExpect(status().isUnauthorized());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private String signIn(String name, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private boolean signInFails(String name, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus() != 200;
    }

    private Long createUser(String name, String role) throws Exception {
        mvc.perform(post("/api/users").header("Authorization", asSuper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        jdbc.update("update users set role = ? where name = ?", role, name);
        return jdbc.queryForObject("select id from users where name = ?", Long.class, name);
    }

    private String openSession(String token) throws Exception {
        byte[] workbook = Files.readAllBytes(Path.of("src/test/resources/test_data.xlsx"));
        String body = mvc.perform(multipart("/api/chat/sessions")
                        .file(new MockMultipartFile("file", "matrix.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("sessionId").asText();
    }

    /** @param openAiKey a key to set, {@code ""} to clear it, or null for "do not mention it" */
    private String settingsBody(String openAiKey) {
        String keys = openAiKey == null ? "{}" : "{\"OPENAI\":\"" + openAiKey + "\"}";
        return """
                {"providerMode":"CLOUD",
                 "local":{"provider":"OLLAMA","baseUrl":"http://localhost:11434","model":"llama3.1"},
                 "cloud":{"activeProvider":"OPENAI","apiKeys":%s,"models":{"OPENAI":"gpt-4o"}}}"""
                .formatted(keys);
    }

    private static org.springframework.test.web.servlet.result.StatusResultMatchers status() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}
