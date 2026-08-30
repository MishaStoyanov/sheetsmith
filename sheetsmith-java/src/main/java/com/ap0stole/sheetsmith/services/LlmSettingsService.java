package com.ap0stole.sheetsmith.services;

import org.springframework.beans.factory.annotation.Value;
import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.entity.LlmSettingsEntity;
import com.ap0stole.sheetsmith.repository.LlmSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which model this instance talks to, and with whose key.
 * <p>
 * <strong>Two readers, two methods, and the difference is the point.</strong> {@link #active()} is
 * the one the planner and the chat call on every request — it carries the real keys and cannot be
 * guarded by a role, because an ordinary user running an ordinary job needs the instance's
 * credentials to be used on their behalf. {@link #getSettings()} is the one the settings screen
 * calls, and it is the superadmin's alone and carries no keys at all. One method serving both is
 * how a stored API key ends up in every signed-in person's browser.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSettingsService {

    private final LlmSettingsRepository repository;
    private final ObjectMapper objectMapper;

    /** Where "now" comes from, so a test can decide what it is. */
    private final Clock clock;

    /**
     * What the running instance was configured with. Spring AI reads the same two properties, so a
     * user who set OLLAMA_MODEL gets that model rather than whatever this class once hardcoded.
     */
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String configuredBaseUrl;

    @Value("${spring.ai.ollama.chat.options.model:llama3.1}")
    private String configuredModel;

    /**
     * What the application actually calls the model with, keys included.
     * <p>
     * Deliberately unguarded: this runs on the path of every plan and every chat turn, on behalf of
     * whoever is working. It is not exposed by any endpoint.
     */
    @Transactional(readOnly = true)
    public LlmSettingsDto active() {
        return stored();
    }

    /** The same read, reachable from the methods in this class without going out through the proxy. */
    private LlmSettingsDto stored() {
        return repository.findById(LlmSettingsEntity.GLOBAL_ID)
                .map(this::deserialize)
                .orElseGet(() -> LlmSettingsDto.defaults(configuredBaseUrl, configuredModel));
    }

    /** The same settings for the screen: no keys, only which providers have one. */
    @Transactional(readOnly = true)
    public LlmSettingsDto getSettings() {
        return withoutKeys(stored());
    }

    /**
     * Saves what was sent, keeping the keys that were not.
     * <p>
     * A blank key means "leave the stored one alone", because that is what the screen sends for a
     * key it never received. Clearing one is done by sending the provider a blank <em>and</em>
     * naming it in {@code apiKeys} — see the tests — so "I did not touch this" and "remove this"
     * stay different instructions.
     */
    @Transactional
    public LlmSettingsDto updateSettings(LlmSettingsDto dto) {
        LlmSettingsDto merged = withKeptKeys(dto);
        String json = serialize(merged);
        LlmSettingsEntity entity = repository.findById(LlmSettingsEntity.GLOBAL_ID)
                .orElseGet(() -> LlmSettingsEntity.of(LlmSettingsEntity.GLOBAL_ID, json));
        entity.setSettingsJson(json);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        repository.save(entity);
        log.info("LLM settings updated: providerMode={}", merged.providerMode());
        return withoutKeys(merged);
    }

    // ── Keys in, never out ────────────────────────────────────────────────────

    private LlmSettingsDto withoutKeys(LlmSettingsDto dto) {
        LlmSettingsDto.CloudSettings cloud = dto.cloud();
        if (cloud == null) {
            return dto;
        }
        Set<String> saved = cloud.apiKeys() == null ? Set.of() : cloud.apiKeys().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        return new LlmSettingsDto(dto.providerMode(), dto.local(),
                new LlmSettingsDto.CloudSettings(cloud.activeProvider(), Map.of(), cloud.models(), saved));
    }

    private LlmSettingsDto withKeptKeys(LlmSettingsDto dto) {
        LlmSettingsDto.CloudSettings cloud = dto.cloud();
        if (cloud == null) {
            return dto;
        }
        LlmSettingsDto.CloudSettings saved = stored().cloud();
        Map<String, String> stored = saved == null || saved.apiKeys() == null
                ? Map.of() : saved.apiKeys();
        Map<String, String> keys = new HashMap<>(stored);
        if (cloud.apiKeys() != null) {
            cloud.apiKeys().forEach((provider, key) -> {
                if (key != null && !key.isBlank()) {
                    keys.put(provider, key);
                } else if (stored.containsKey(provider)) {
                    // Named, and blank: the one way to say "remove it".
                    keys.remove(provider);
                }
            });
        }
        return new LlmSettingsDto(dto.providerMode(), dto.local(),
                new LlmSettingsDto.CloudSettings(cloud.activeProvider(), keys, cloud.models(), Set.of()));
    }

    private LlmSettingsDto deserialize(LlmSettingsEntity entity) {
        try {
            return objectMapper.readValue(entity.getSettingsJson(), LlmSettingsDto.class);
        } catch (Exception e) {
            log.error("Failed to parse stored LLM settings, falling back to defaults", e);
            return LlmSettingsDto.defaults(configuredBaseUrl, configuredModel);
        }
    }

    private String serialize(LlmSettingsDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize LLM settings", e);
        }
    }
}
