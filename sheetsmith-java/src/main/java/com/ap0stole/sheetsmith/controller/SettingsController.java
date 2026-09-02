package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.dto.CloudModelsResponseDto;
import com.ap0stole.sheetsmith.domain.dto.OllamaModelsResponseDto;
import com.ap0stole.sheetsmith.domain.dto.StorageSettingsDto;
import com.ap0stole.sheetsmith.services.LlmSettingsService;
import com.ap0stole.sheetsmith.services.CloudModelService;
import com.ap0stole.sheetsmith.services.OllamaModelService;
import com.ap0stole.sheetsmith.services.StorageSettingsService;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this instance is configured to do: which model it calls and with whose key, and where it
 * keeps the files it is given.
 * <p>
 * The rule is on the class because it is the same for every endpoint here and always will be: this
 * is the machine's own configuration, not anybody's data. A folder is a path on the server, and a
 * key is a credential the instance spends money with.
 */
@Validated
@PreAuthorize("@authz.superadmin()")
@Tag(name = "Settings", description = "The instance’s own configuration: which model it calls, with whose key, and where it keeps the files. Superadmin only.")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final LlmSettingsService llmSettingsService;
    private final OllamaModelService ollamaModelService;
    private final CloudModelService cloudModelService;
    private final StorageSettingsService storageSettingsService;

    @Operation(summary = "The model settings, without the keys",
            description = "Reports which providers have a key stored, never what it is.")
    @GetMapping
    public ResponseEntity<LlmSettingsDto> get() {
        return ResponseEntity.ok(llmSettingsService.getSettings());
    }

    @Operation(summary = "Change the model settings",
            description = "A blank key leaves the stored one alone - the screen never received it and cannot send it back. Naming a provider with a blank key is how a key is removed.")
    @PutMapping
    public ResponseEntity<LlmSettingsDto> update(@RequestBody LlmSettingsDto dto) {
        return ResponseEntity.ok(llmSettingsService.updateSettings(dto));
    }

    /** Where the files are kept and how much of them to keep. */
    @Operation(summary = "Where the files are kept, and how much of them",
            description = "Also reports what is on disk right now, measured rather than counted in the history.")
    @GetMapping("/storage")
    public ResponseEntity<StorageSettingsDto> storage() {
        return ResponseEntity.ok(storageSettingsService.get());
    }

    @Operation(summary = "Choose the folder and the caps",
            description = "The folder is proved writable before it is saved. Null means unset, not zero: no cap, and the directories the instance started with. Changing the folder moves nothing.")
    @ApiResponse(responseCode = "400", description = "A folder the server cannot create files in — proved by writing one, not by asking the filesystem — or a cap of zero, which would mean deleting every run as it finished.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PutMapping("/storage")
    public ResponseEntity<StorageSettingsDto> updateStorage(@RequestBody StorageSettingsDto.Update update) {
        return ResponseEntity.ok(storageSettingsService.update(update));
    }

    @Operation(summary = "Ask a cloud vendor which models it will answer to",
            description = "Uses the key already saved for that provider; a key is never accepted as a parameter. Names that cannot hold a conversation — embeddings, speech, images — are left out.")
    @ApiResponse(responseCode = "502", description = "No key is saved for that provider, or the vendor could not be reached.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @GetMapping("/cloud/models")
    public ResponseEntity<CloudModelsResponseDto> listCloudModels(@RequestParam @NotBlank String provider) {
        return ResponseEntity.ok(new CloudModelsResponseDto(cloudModelService.listModels(provider)));
    }

    @Operation(summary = "Ask an Ollama server what it has")
    @ApiResponse(responseCode = "502", description = "That Ollama server could not be reached.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @GetMapping("/ollama/models")
    public ResponseEntity<OllamaModelsResponseDto> listOllamaModels(@RequestParam @NotBlank String baseUrl) {
        return ResponseEntity.ok(new OllamaModelsResponseDto(ollamaModelService.listModels(baseUrl)));
    }
}
