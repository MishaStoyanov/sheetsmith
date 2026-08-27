package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;

/**
 * Which engine answered a call: the mode the instance was in, and the model that actually ran.
 * <p>
 * Recorded alongside the token count because the two are only meaningful together — the same
 * number of tokens is a rounding error on a local model and a bill on a cloud one.
 */
public record LlmEngine(String providerMode, String model) {

    public static final LlmEngine UNKNOWN = new LlmEngine(null, null);

    /**
     * Reads the engine out of the settings the call is about to use, so it records what ran rather
     * than what is configured now — the user is free to change providers between two calls.
     */
    public static LlmEngine of(LlmSettingsDto settings) {
        if (settings == null) {
            return UNKNOWN;
        }
        if ("CLOUD".equals(settings.providerMode())) {
            LlmSettingsDto.CloudSettings cloud = settings.cloud();
            String model = (cloud == null || cloud.models() == null)
                    ? null
                    : cloud.models().get(cloud.activeProvider());
            return new LlmEngine("CLOUD", model);
        }
        return new LlmEngine("LOCAL", settings.local() == null ? null : settings.local().model());
    }

    public boolean isKnown() {
        return providerMode != null || model != null;
    }
}
