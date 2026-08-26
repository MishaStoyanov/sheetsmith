package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a fresh instance talks to before anyone opens the settings panel.
 * <p>
 * This used to be a hardcoded pair, and the consequence only showed up when the documented quick
 * start was actually run: <code>docker compose up</code> started Ollama, pulled the model into it,
 * and the app then asked a different server for a different model — because it had ignored
 * <code>OLLAMA_BASE_URL</code> and <code>OLLAMA_MODEL</code>, the two values the README, the compose
 * file and <code>.env.example</code> all tell a user to set.
 */
class LlmSettingsDefaultsTest {

    @Test
    @DisplayName("the defaults are whatever this instance was configured with")
    void defaultsFollowTheConfiguration() {
        LlmSettingsDto settings = LlmSettingsDto.defaults("http://ollama:11434", "qwen2.5:0.5b");

        assertThat(settings.local().baseUrl()).isEqualTo("http://ollama:11434");
        assertThat(settings.local().model()).isEqualTo("qwen2.5:0.5b");
        assertThat(settings.providerMode()).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("the no-argument form matches application-ollama.yaml's own fallbacks")
    void theBareDefaultsMatchTheYaml() {
        LlmSettingsDto settings = LlmSettingsDto.defaults();

        // application-ollama.yaml: base-url ${OLLAMA_BASE_URL:http://localhost:11434}
        //                          model    ${OLLAMA_MODEL:llama3.1}
        assertThat(settings.local().baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(settings.local().model()).isEqualTo("llama3.1");
    }

    @Test
    @DisplayName("a host that is not there is never assumed — that was the whole bug")
    void nothingIsHardcodedToADockerHostname() {
        assertThat(LlmSettingsDto.defaults().local().baseUrl())
                .as("host.docker.internal does not resolve outside Docker, and is wrong inside it "
                        + "whenever compose runs its own Ollama")
                .doesNotContain("host.docker.internal");
    }
}
