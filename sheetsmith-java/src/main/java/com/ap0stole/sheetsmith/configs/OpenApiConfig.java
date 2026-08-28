package com.ap0stole.sheetsmith.configs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's own description, generated from the code that serves it.
 * <p>
 * Written from the handlers rather than kept as a document beside them, because a hand-maintained
 * API reference describes the endpoints somebody remembered to update. The one thing added by hand
 * is the paragraph below: what the two modes mean and who may call what, which is the first question
 * anybody reading this has and the one an annotation on a method cannot answer.
 * <p>
 * The scheme is declared globally, so the <em>Authorize</em> button in Swagger UI sends the bearer
 * token with every try-it-out call. Sign in through {@code POST /api/auth/login} first and paste the
 * {@code accessToken} it returns.
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final AuthConfig authConfig;

    @Bean
    public OpenAPI sheetsmithOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SheetSmith API")
                        .version("v1")
                        .description(description())
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("""
                                The access token from POST /api/auth/login. It lives two hours; the \
                                refresh token is an httpOnly cookie the browser sends by itself and \
                                is not used here.""")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    private String description() {
        String mode = authConfig.isEnabled()
                ? "This instance **requires a login**: everything under `/api` needs a bearer token."
                : "This instance runs **without accounts** — the default. Every endpoint answers "
                        + "without a token, and runs are recorded with no owner, because there is "
                        + "nobody to name. Set `SHEETSMITH_AUTH_ENABLED=true` for the other mode.";

        return mode + """


                ### Who may call what

                Each endpoint carries its own rule, shown in this document as the roles that satisfy \
                it. The ladder is short: an ordinary **user** works on their own spreadsheets; an \
                **admin** also manages ordinary users — but not a peer and not the seeded account; \
                the **superadmin** is that seeded first account, and is alone in being able to \
                demote, to delete anything, and to change the instance's own configuration: the \
                model and its API keys, where files are kept, and what a token costs.

                With authentication off there is nobody to refuse, so every rule answers yes: the \
                person at the keyboard is the operator by definition.

                ### What is not here

                Stored API keys are never returned by any endpoint. `GET /api/settings` reports \
                which providers have one; a blank key on save leaves the stored one alone, and \
                naming it blank is how you remove it.""";
    }
}
