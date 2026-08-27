package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;

/**
 * Which engine answered a call: the mode the instance was in, the vendor behind it, and the model
 * that actually ran.
 * <p>
 * Recorded alongside the token count because the two are only meaningful together — the same
 * number of tokens is a rounding error on a local model and a bill on a cloud one. The vendor is
 * kept as its own field rather than read off the model name: "gemini-3.7-flash" implies Google only
 * by convention, and a convention is not something to build a spend chart on.
 */
public record LlmEngine(String providerMode, String provider, String model) {

    public static final LlmEngine UNKNOWN = new LlmEngine(null, null, null);

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
            if (cloud == null) {
                return new LlmEngine("CLOUD", null, null);
            }
            String model = cloud.models() == null ? null : cloud.models().get(cloud.activeProvider());
            return new LlmEngine("CLOUD", cloud.activeProvider(), model);
        }
        LlmSettingsDto.LocalSettings local = settings.local();
        // The vendor of a local run is its runtime. Naming it keeps the column answerable for every
        // row, so a spend chart has a labelled slice for local work rather than a gap.
        return local == null
                ? new LlmEngine("LOCAL", null, null)
                : new LlmEngine("LOCAL", local.provider(), local.model());
    }

    public boolean isKnown() {
        return providerMode != null || provider != null || model != null;
    }
}
