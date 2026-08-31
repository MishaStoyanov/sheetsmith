package com.ap0stole.sheetsmith.configs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API document describes the instance serving it, and the two descriptions must not overlap.
 * <p>
 * This exists because they did. The preamble is assembled from a mode line and a block of prose,
 * and the sentence saying nobody is refused had been written into the block — so a secured instance
 * published "every rule answers yes: the person at the keyboard is the operator by definition"
 * directly beneath "this instance requires a login". Both halves were served to every reader of
 * {@code /v3/api-docs}, and the reassuring half was the wrong one.
 * <p>
 * Nothing caught it because nothing read the description: the endpoint was covered, the text it
 * returns was not. So the assertions below are mostly negative — what each mode must <em>not</em>
 * say — since a contradiction is not a missing sentence but a surplus one.
 */
class OpenApiDescriptionTest {

    private static String descriptionWithAuth(boolean enabled) {
        AuthConfig auth = new AuthConfig();
        auth.setEnabled(enabled);
        return new OpenApiConfig(auth).sheetsmithOpenApi().getInfo().getDescription();
    }

    @Test
    @DisplayName("an instance without accounts says so, and says what that means for the rules")
    void withoutAccounts() {
        String description = descriptionWithAuth(false);

        assertThat(description)
                .contains("without accounts")
                .contains("With authentication off there is nobody to refuse")
                .doesNotContain("requires a login");
    }

    @Test
    @DisplayName("an instance that requires a login never claims every rule answers yes")
    void requiringALogin() {
        String description = descriptionWithAuth(true);

        assertThat(description)
                .contains("requires a login")
                .contains("Every rule is enforced against the bearer token")
                .doesNotContain("With authentication off")
                .doesNotContain("every rule answers yes")
                .doesNotContain("operator by definition");
    }

    @Test
    @DisplayName("the ladder itself is the same sentence in both modes")
    void theLadderDoesNotMove() {
        String ladder = "The ladder is short: an ordinary **user** works on their own spreadsheets";

        assertThat(descriptionWithAuth(false)).contains(ladder);
        assertThat(descriptionWithAuth(true)).contains(ladder);
    }
}
