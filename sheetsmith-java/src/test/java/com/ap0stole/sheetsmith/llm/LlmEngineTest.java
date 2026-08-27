package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which model a run used has to come out of the settings the call is about to use — the cloud half
 * keeps a model per provider, so reading the wrong one records a model that never ran.
 */
class LlmEngineTest {

    @Test
    @DisplayName("local mode records the Ollama model by name")
    void readsTheLocalModel() {
        LlmEngine engine = LlmEngine.of(LlmSettingsDto.defaults("http://localhost:11434", "gemma4:12b"));

        assertThat(engine.providerMode()).isEqualTo("LOCAL");
        // The vendor of a local run is its runtime, so a spend chart gets a named slice for local
        // work instead of an unlabelled gap.
        assertThat(engine.provider()).isEqualTo("OLLAMA");
        assertThat(engine.model()).isEqualTo("gemma4:12b");
    }

    @Test
    @DisplayName("cloud mode records the active provider's model, not the whole map")
    void readsTheActiveCloudModel() {
        LlmSettingsDto settings = new LlmSettingsDto(
                "CLOUD",
                new LlmSettingsDto.LocalSettings("OLLAMA", "http://localhost:11434", "gemma4:12b"),
                new LlmSettingsDto.CloudSettings("GEMINI", Map.of("GEMINI", "key"),
                        Map.of("OPENAI", "gpt-4o", "GEMINI", "gemini-3.7-flash")));

        LlmEngine engine = LlmEngine.of(settings);

        assertThat(engine.providerMode()).isEqualTo("CLOUD");
        assertThat(engine.provider()).isEqualTo("GEMINI");
        assertThat(engine.model()).isEqualTo("gemini-3.7-flash");
    }

    @Test
    @DisplayName("a provider with no model configured is still recorded as cloud")
    void recordsTheModeEvenWithoutAModel() {
        LlmSettingsDto settings = new LlmSettingsDto(
                "CLOUD",
                new LlmSettingsDto.LocalSettings("OLLAMA", "http://localhost:11434", "gemma4:12b"),
                new LlmSettingsDto.CloudSettings("DEEPSEEK", Map.of(), Map.of()));

        LlmEngine engine = LlmEngine.of(settings);

        assertThat(engine.providerMode()).isEqualTo("CLOUD");
        assertThat(engine.provider()).isEqualTo("DEEPSEEK");
        assertThat(engine.model()).isNull();
        assertThat(engine.isKnown()).isTrue();
    }

    @Test
    @DisplayName("no settings at all is unknown, not a guess")
    void missingSettingsAreUnknown() {
        assertThat(LlmEngine.of(null).isKnown()).isFalse();
        assertThat(LlmEngine.UNKNOWN.isKnown()).isFalse();
    }
}
