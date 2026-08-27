package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.prompt.FrequentPromptDto;
import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import com.ap0stole.sheetsmith.services.PromptHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Your own past phrasings.
 * <p>
 * There is no parameter for whose prompts to return, and deliberately so: the answer is always the
 * caller's. An endpoint that took an owner would be one authorisation check away from handing one
 * person's description of their own spreadsheet to another, and this is the one place on the
 * instance where that is not a hypothetical.
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptHistoryService promptHistory;

    @GetMapping("/frequent")
    public List<FrequentPromptDto> frequent(@RequestParam(defaultValue = "IMPROVE") UsageKind kind,
                                            @RequestParam(defaultValue = "5") int limit) {
        return promptHistory.frequent(kind, limit);
    }
}
