package com.ap0stole.sheetsmith.services.catalogue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names on the right of the vendor map, which are not ours to choose.
 * <p>
 * A price is keyed on provider plus model and matched against what a run wrote into the audit. What
 * a run writes is the key of the vendor's slot in the settings — so these strings have to be those
 * keys and nothing else. A tidier-looking name here is a price that applies to nothing.
 * <p>
 * This lives in its own test because the comparison tests stub the catalogue out entirely and hand
 * the service names that are already correct: a mistake in this map is invisible to all of them.
 * That is how ANTHROPIC — a name nothing in this application has ever written — survived being
 * committed.
 */
class OpenRouterCatalogueVendorTest {

    @Test
    @DisplayName("Anthropic is recorded as CLAUDE, because that is the settings slot's name")
    void anthropicIsClaude() {
        assertThat(OpenRouterCatalogue.providerFor("anthropic/claude-sonnet-4")).isEqualTo("CLAUDE");
    }

    @Test
    @DisplayName("the other three map to the names the settings screen uses")
    void theRestMatchTheSettingsKeys() {
        assertThat(OpenRouterCatalogue.providerFor("openai/gpt-4o")).isEqualTo("OPENAI");
        assertThat(OpenRouterCatalogue.providerFor("google/gemini-2.5-flash")).isEqualTo("GEMINI");
        assertThat(OpenRouterCatalogue.providerFor("deepseek/deepseek-chat")).isEqualTo("DEEPSEEK");
    }

    @Test
    @DisplayName("a vendor this instance cannot call is skipped rather than guessed at")
    void unknownVendorsAreSkipped() {
        // The catalogue lists hundreds. Offering a price for something nothing here can call would
        // be a row somebody has to read past before confirming the ones that matter.
        assertThat(OpenRouterCatalogue.providerFor("meta-llama/llama-4-70b")).isNull();
        assertThat(OpenRouterCatalogue.providerFor("mistralai/mistral-large")).isNull();
    }

    @Test
    @DisplayName("an id in a shape the catalogue has never used answers null rather than throwing")
    void malformedIdsAreNotAnException() {
        assertThat(OpenRouterCatalogue.providerFor(null)).isNull();
        assertThat(OpenRouterCatalogue.providerFor("gpt-4o")).isNull();
        assertThat(OpenRouterCatalogue.providerFor("")).isNull();
    }

    @Test
    @DisplayName("the vendor half is matched without regard to case")
    void caseDoesNotMatter() {
        assertThat(OpenRouterCatalogue.providerFor("OpenAI/gpt-4o")).isEqualTo("OPENAI");
    }
}
