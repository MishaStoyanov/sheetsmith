package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.domain.dto.ErrorResponse;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes each endpoint's authorization rule into its own documentation, read from the rule itself.
 * <p>
 * The alternative was a sentence typed into every {@code @Operation} by hand, and a hand-typed
 * sentence about permissions is a sentence that will one day be wrong — which is worse than absent,
 * because somebody will plan around it. Here the {@code @PreAuthorize} on the handler is the source:
 * change the guard and the page changes with it, and an endpoint whose rule nobody wrote cannot
 * quietly document itself as open.
 * <p>
 * The same pass attaches the refusals every guarded call can produce — 401 without a token, 403 with
 * the wrong role — so the error contract is on the page rather than in somebody's memory, and takes
 * the bearer requirement <em>off</em> the four endpoints that must answer before anybody is signed
 * in. A "sign in" operation demanding a token is the sort of detail that makes a reader distrust the
 * rest of the document.
 */
@Component
public class OpenApiRulesCustomizer implements OperationCustomizer {

    /** The rules as they are written, and the same rules as a person would say them. */
    private static final Map<String, String> RULES = Map.of(
            "@authz.signedIn()", "any signed-in account",
            "@authz.admin()", "administrators and the superadmin",
            "@authz.superadmin()", "the superadmin only — the seeded first account",
            "@access.maySeeSession(#sessionId)",
            "whoever opened the document; an administrator may also open an ordinary user's, "
                    + "and the superadmin anyone's",
            "@access.maySeeSession(#request.sessionId())",
            "whoever opened the document; an administrator may also open an ordinary user's, "
                    + "and the superadmin anyone's");

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        }

        if (rule == null) {
            // The handful that answer before anybody is signed in. Said out loud, because "no rule"
            // and "a rule I forgot" look identical on a page that stays silent about both.
            operation.setSecurity(java.util.List.of());
            append(operation, "**Open:** answers without a token, in both authentication modes.");
            return operation;
        }

        String said = RULES.get(rule.value().trim());
        append(operation, "**Requires:** " + (said == null ? "`" + rule.value() + "`" : said)
                + ". With authentication switched off there is nobody to refuse, so this answers "
                + "for anybody — the person at the keyboard is the operator by definition.");
        refusals(operation);
        return operation;
    }

    private void append(Operation operation, String sentence) {
        String existing = operation.getDescription() == null ? "" : operation.getDescription() + "\n\n";
        operation.setDescription(existing + sentence);
    }

    /** The two answers every guarded endpoint can give, described once and attached everywhere. */
    private void refusals(Operation operation) {
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        responses.addApiResponse("401", error("No token, or one that has expired. The browser's "
                + "silent refresh keys off this: retry once against /api/auth/refresh."));
        responses.addApiResponse("403", error("Signed in, and still not allowed. A retry cannot fix "
                + "it; the answer names no rule, because describing the instance to somebody who may "
                + "not see it is its own leak."));
        operation.setResponses(responses);
    }

    private ApiResponse error(String description) {
        Schema<?> schema = new Schema<>().$ref("#/components/schemas/ErrorResponse");
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("code", "FORBIDDEN");
        example.put("message", "You are not allowed to do that.");
        example.put("field", null);
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(schema).example(example)));
    }

    /** Referenced so the component is registered even where no handler returns it by type. */
    @SuppressWarnings("unused")
    private static final Class<ErrorResponse> ERROR_SHAPE = ErrorResponse.class;
}
