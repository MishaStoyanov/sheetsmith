package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.dto.OllamaModelsResponseDto;
import com.ap0stole.sheetsmith.domain.dto.StorageSettingsDto;
import com.ap0stole.sheetsmith.services.LlmSettingsService;
import com.ap0stole.sheetsmith.services.OllamaModelService;
import com.ap0stole.sheetsmith.services.StorageSettingsService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final LlmSettingsService llmSettingsService;
    private final OllamaModelService ollamaModelService;
    private final StorageSettingsService storageSettingsService;

    @GetMapping
    public ResponseEntity<LlmSettingsDto> get() {
        return ResponseEntity.ok(llmSettingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<LlmSettingsDto> update(@RequestBody LlmSettingsDto dto) {
        return ResponseEntity.ok(llmSettingsService.updateSettings(dto));
    }

    /**
     * Where the files are kept and how much of them to keep.
     * <p>
     * Under settings because that is what it is, but guarded in the service rather than here: the
     * refusal has to hold for a request that never went through this controller.
     */
    @GetMapping("/storage")
    public ResponseEntity<StorageSettingsDto> storage() {
        return ResponseEntity.ok(storageSettingsService.get());
    }

    @PutMapping("/storage")
    public ResponseEntity<StorageSettingsDto> updateStorage(@RequestBody StorageSettingsDto.Update update) {
        return ResponseEntity.ok(storageSettingsService.update(update));
    }

    @GetMapping("/ollama/models")
    public ResponseEntity<OllamaModelsResponseDto> listOllamaModels(@RequestParam @NotBlank String baseUrl) {
        return ResponseEntity.ok(new OllamaModelsResponseDto(ollamaModelService.listModels(baseUrl)));
    }
}
