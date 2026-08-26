package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.domain.dto.CapabilitiesDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this instance can do, for a UI that must not offer what the server has not got.
 * <p>
 * Kept apart from {@code /api/settings}, which is the user's editable LLM configuration: this is
 * read-only and decided at startup, and mixing the two would invite a PUT that appears to turn the
 * chat back on.
 */
@RestController
@RequestMapping("/api/capabilities")
@RequiredArgsConstructor
public class CapabilitiesController {

    private final ChatConfig chatConfig;

    @GetMapping
    public ResponseEntity<CapabilitiesDto> capabilities() {
        return ResponseEntity.ok(CapabilitiesDto.of(chatConfig.isEnabled()));
    }
}
