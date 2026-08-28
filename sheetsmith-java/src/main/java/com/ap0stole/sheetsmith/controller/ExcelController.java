package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import com.ap0stole.sheetsmith.domain.dto.ApplyPlanRequest;
import com.ap0stole.sheetsmith.domain.dto.DescribeStepsRequest;
import com.ap0stole.sheetsmith.domain.dto.ImproveByPathRequest;
import com.ap0stole.sheetsmith.domain.dto.PlanRequest;
import com.ap0stole.sheetsmith.domain.dto.PlanResponseDto;
import com.ap0stole.sheetsmith.domain.dto.SuggestRequest;
import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.services.JobService;
import com.ap0stole.sheetsmith.services.chat.SuggestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
public class ExcelController {

    private final JobService jobService;
    private final DocumentSessionService sessionService;
    /**
     * Absent when the chat is off: the inspection behind /suggest runs the chat's read-only query
     * tools over the actual data, which is exactly what such an instance exists not to do.
     */
    private final ObjectProvider<SuggestionService> suggestionService;

    @PostMapping("/improve")
    public ResponseEntity<Map<String, Long>> improve(
            @RequestParam("file") MultipartFile file,
            @RequestParam("instruction") @NotBlank @Size(max = 2000) String instruction) throws IOException {

        Long jobId = jobService.createAndSubmit(file, instruction);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    /** Plans against a session's current revision; {@code /apply} then commits the next one. */
    @PostMapping("/plan")
    public ResponseEntity<PlanResponseDto> plan(@RequestBody @Valid PlanRequest request) {
        sessionService.requireVisible(request.sessionId());
        return ResponseEntity.ok(jobService.generatePlan(request));
    }

    /**
     * A plan for a user with nothing to type yet: the sheet is inspected with the chat's read-only
     * tools first, so the suggestions come from the data rather than from the column names. That
     * inspection reads real cell values, which is why this endpoint is part of the chat for the
     * purposes of {@code sheetsmith.chat.enabled} even though it lives here.
     */
    @PostMapping("/suggest")
    public ResponseEntity<PlanResponseDto> suggest(@RequestBody @Valid SuggestRequest request) {
        SuggestionService suggestions = suggestionService.getIfAvailable();
        if (suggestions == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Suggestions read the sheet's data to ground themselves, so they are unavailable"
                            + " on an instance running with the chat turned off. Describe the change"
                            + " you want instead.");
        }
        sessionService.requireVisible(request.sessionId());
        return ResponseEntity.ok(suggestions.suggest(request.sessionId()));
    }

    /**
     * Re-narrates edited steps. The review cards call this after the user changes a range or a
     * formula, so the sentence on the card always matches what will actually run.
     */
    @PostMapping("/describe")
    public ResponseEntity<PlanResponseDto> describe(@RequestBody @Valid DescribeStepsRequest request) {
        return ResponseEntity.ok(new PlanResponseDto(null, jobService.describeSteps(request.steps())));
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Long>> apply(@RequestBody ApplyPlanRequest request) {
        Long jobId = jobService.applyPlan(request);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    /**
     * Disabled by default: it reads and writes server-side paths, so
     * {@code PathGuard} (via {@code JobService}) decides whether the request may run at all.
     */
    @PostMapping("/improve/path")
    public ResponseEntity<Map<String, Long>> improveByPath(
            @RequestBody @Valid ImproveByPathRequest request) {

        Long jobId = jobService.createAndSubmitByPath(request);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }
}
