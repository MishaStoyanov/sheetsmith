package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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

    /** The security scheme's name, written once for the three places that refer to it. */
    private static final String BEARER = "bearer";

    private final AuthConfig authConfig;

    @Bean
    public OpenAPI sheetsmithOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SheetSmith API")
                        .version("v1")
                        .description(description())
                        .contact(new Contact().name("SheetSmith").url("https://github.com/MishaStoyanov/sheetsmith"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .externalDocs(new ExternalDocumentation()
                        .description("The long-form reference: setup, the action catalogue, and why each rule is the way it is")
                        .url("https://github.com/MishaStoyanov/sheetsmith#readme"))
                .servers(List.of(new Server().url("/").description("This instance")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme(BEARER)
                        .bearerFormat("JWT")
                        .description("""
                                The access token from POST /api/auth/login. It lives two hours; the \
                                refresh token is an httpOnly cookie the browser sends by itself and \
                                is not used here.""")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * The parts of the document that are the same for every endpoint: the shape of a refusal, and
     * an order for the tags that follows how somebody actually meets the API.
     * <p>
     * The error schema is written here rather than inferred from a return type, because no handler
     * returns it on the happy path — it is what the advice produces — so a generator walking method
     * signatures would never see it. Its {@code code} carries the whole enum: the list is the
     * contract a client writes a switch against, and one missing value is a client that falls
     * through to "unknown error" on a case the server considers ordinary.
     */
    @Bean
    public OpenApiCustomizer sheetsmithSharedShapes() {
        return openApi -> {
            openApi.getComponents().addSchemas("ErrorResponse", errorSchema());
            if (openApi.getTags() != null) {
                openApi.getTags().sort(Comparator.comparingInt(tag -> order(tag.getName())));
            }
        };
    }

    /** Upload first, then the work, then the money, then the machine — the order people meet it in. */
    private static final List<String> TAG_ORDER = List.of(
            "Capabilities", "Auth", "Sessions", "Excel", "Chat", "History",
            "Analytics", "Prompts", "Accounts", "Prices", "Settings");

    private int order(String tag) {
        int at = TAG_ORDER.indexOf(tag);
        return at < 0 ? TAG_ORDER.size() : at;
    }

    private Schema<?> errorSchema() {
        StringSchema code = new StringSchema();
        code.setDescription("What kind of refusal this is. Stable across versions and safe to switch on"
                + " — the message is written for a person and may be reworded.");
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(code::addEnumItemObject);
        code.setExample("VALIDATION_ERROR");

        StringSchema message = new StringSchema();
        message.setDescription("One sentence, meant to be shown to whoever made the request.");
        message.setExample("A limit of zero spreadsheets would delete every run as it finished.");

        StringSchema field = new StringSchema();
        field.setNullable(true);
        field.setDescription("The field the refusal is about, where it is about one — so a form can put"
                + " the message beside the box rather than at the top of the page.");
        field.setExample("maxFiles");

        ObjectSchema error = new ObjectSchema();
        error.setDescription("Every refusal from this API has this shape, whatever produced it: a code"
                + " to act on, a sentence to show, and sometimes the field at fault.");
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("field", field);
        error.setRequired(List.of("code", "message"));
        return error;
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
