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

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSettingsService {

    private final LlmSettingsRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * What the running instance was configured with. Spring AI reads the same two properties, so a
     * user who set OLLAMA_MODEL gets that model rather than whatever this class once hardcoded.
     */
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String configuredBaseUrl;

    @Value("${spring.ai.ollama.chat.options.model:llama3.1}")
    private String configuredModel;

    @Transactional(readOnly = true)
    public LlmSettingsDto getSettings() {
        return repository.findById(LlmSettingsEntity.GLOBAL_ID)
                .map(this::deserialize)
                .orElseGet(() -> LlmSettingsDto.defaults(configuredBaseUrl, configuredModel));
    }

    @Transactional
    public LlmSettingsDto updateSettings(LlmSettingsDto dto) {
        String json = serialize(dto);
        LlmSettingsEntity entity = repository.findById(LlmSettingsEntity.GLOBAL_ID)
                .orElseGet(() -> LlmSettingsEntity.of(LlmSettingsEntity.GLOBAL_ID, json));
        entity.setSettingsJson(json);
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        log.info("LLM settings updated: providerMode={}", dto.providerMode());
        return dto;
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
