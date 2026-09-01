package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.controller.CapabilitiesController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the filter chain lets through, in both of the shapes this app has.
 * <p>
 * The preflight cases are the reason this file exists. CORS used to be an MVC mapping, applied by
 * the dispatcher — which a security filter chain sits in front of. A preflight {@code OPTIONS}
 * carries no credentials by definition, so a chain that requires authentication for everything
 * answers it before MVC is reached, and the browser reports a CORS failure for a rule that was
 * configured perfectly. Nothing on the server looks wrong; the only symptom is in someone's
 * browser console.
 */
class SecurityChainTest {

    @Nested
    @WebMvcTest(controllers = CapabilitiesController.class)
    @Import({AuthConfig.class, SecurityProperties.class, SecurityConfig.class, ChatConfig.class,
            AuthTestBeans.class})
    @DisplayName("with authentication off — the default")
    class AuthOff {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("the API is open, exactly as it was before a chain existed")
        void everythingIsPermitted() throws Exception {
            mockMvc.perform(get("/api/capabilities")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("an allowed origin is answered with permission to read the response")
        void corsAllowsAConfiguredOrigin() throws Exception {
            mockMvc.perform(options("/api/capabilities")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }

        @Test
        @DisplayName("an origin nobody configured is still refused — the allowlist is the guard")
        void corsRefusesAnUnknownOrigin() throws Exception {
            // Without a login, this allowlist is the only thing stopping any site the user has open
            // from driving their instance, including rewriting the stored cloud API keys.
            mockMvc.perform(options("/api/capabilities")
                            .header("Origin", "http://evil.example")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @WebMvcTest(controllers = CapabilitiesController.class)
    @Import({AuthConfig.class, SecurityProperties.class, SecurityConfig.class, ChatConfig.class,
            AuthTestBeans.class})
    @TestPropertySource(properties = "sheetsmith.auth.enabled=true")
    @DisplayName("with authentication on")
    class AuthOn {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("preflight still passes, or every cross-origin call dies before it is made")
        void preflightIsNotAuthenticated() throws Exception {
            mockMvc.perform(options("/api/capabilities")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }

        @Test
        @DisplayName("the UI can still ask whether it needs to show a login screen")
        void capabilitiesStayOpen() throws Exception {
            // Chicken and egg: this is the endpoint that tells the browser a login is required, so
            // putting it behind the login would leave the UI with no way to find out.
            mockMvc.perform(get("/api/capabilities")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("an unauthenticated API call is refused")
        void theApiIsClosed() throws Exception {
            mockMvc.perform(get("/api/history")).andExpect(status().isUnauthorized());
        }
    }
}
