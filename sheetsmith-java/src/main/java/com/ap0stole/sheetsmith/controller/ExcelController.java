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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Validated
@Tag(name = "Excel", description = "Planning and applying changes to a spreadsheet, plus the two scripting entry points that own no session.")
@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
public class ExcelController {

    /** The one field a submitted job answers with, named for the three endpoints that answer it. */
    private static final String JOB_ID = "jobId";

    private final JobService jobService;
    private final DocumentSessionService sessionService;
    /**
     * Absent when the chat is off: the inspection behind /suggest runs the chat's read-only query
     * tools over the actual data, which is exactly what such an instance exists not to do.
     */
    private final ObjectProvider<SuggestionService> suggestionService;

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Submit a file and an instruction",
            description = "For automation: owns no session, so its result is not part of any revision chain. Returns a job id to poll.")
    @ApiResponse(responseCode = "400", description = "Not a readable .xlsx.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "402", description = "The spend limit for this account is used up. It lifts on its own at the start of next month.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "413", description = "Larger than the 50 MB upload limit.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "502", description = "The model could not be reached, or answered with something unusable. The failure is somewhere else, and a retry may well work.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/improve")
    public ResponseEntity<Map<String, Long>> improve(
            @RequestParam("file") MultipartFile file,
            @RequestParam("instruction") @NotBlank @Size(max = 2000) String instruction) throws IOException {

        Long jobId = jobService.createAndSubmit(file, instruction);
        return ResponseEntity.accepted().body(Map.of(JOB_ID, jobId));
    }

    /** Plans against a session's current revision; {@code /apply} then commits the next one. */
    @PreAuthorize("@access.maySeeSession(#request.sessionId())")
    @Operation(summary = "Plan against a session",
            description = "Returns the steps in plain language for review. Nothing is written until /apply.")
    @ApiResponse(responseCode = "402", description = "The spend limit for this account is used up.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "404", description = "No such session, or it has expired.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "502", description = "The model could not be reached or answered unusably.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/plan")
    public ResponseEntity<PlanResponseDto> plan(@RequestBody @Valid PlanRequest request) {
        return ResponseEntity.ok(jobService.generatePlan(request));
    }

    /**
     * A plan for a user with nothing to type yet: the sheet is inspected with the chat's read-only
     * tools first, so the suggestions come from the data rather than from the column names. That
     * inspection reads real cell values, which is why this endpoint is part of the chat for the
     * purposes of {@code sheetsmith.chat.enabled} even though it lives here.
     */
    @PreAuthorize("@access.maySeeSession(#request.sessionId())")
    @Operation(summary = "Ask what is worth improving",
            description = "Reads real cell values so the suggestions come from the data rather than the column names - which is why it is unavailable with the chat off.")
    @ApiResponse(responseCode = "400", description = "This instance runs with the chat off, so suggestions — which read real cell values — are unavailable.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "402", description = "The spend limit for this account is used up.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "404", description = "No such session, or it has expired.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/suggest")
    public ResponseEntity<PlanResponseDto> suggest(@RequestBody @Valid SuggestRequest request) {
        SuggestionService suggestions = suggestionService.getIfAvailable();
        if (suggestions == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Suggestions read the sheet's data to ground themselves, so they are unavailable"
                            + " on an instance running with the chat turned off. Describe the change"
                            + " you want instead.");
        }
        return ResponseEntity.ok(suggestions.suggest(request.sessionId()));
    }

    /**
     * Re-narrates edited steps. The review cards call this after the user changes a range or a
     * formula, so the sentence on the card always matches what will actually run.
     */
    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Re-narrate edited steps",
            description = "Called after somebody edits a range on a card, so the sentence always matches what will run.")
    @PostMapping("/describe")
    public ResponseEntity<PlanResponseDto> describe(@RequestBody @Valid DescribeStepsRequest request) {
        return ResponseEntity.ok(new PlanResponseDto(null, jobService.describeSteps(request.steps())));
    }

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Apply a reviewed plan",
            description = "Runs the steps against the session’s current revision and commits the next one.")
    @ApiResponse(responseCode = "400", description = "The plan token is unknown or has already been applied.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "402", description = "The spend limit for this account is used up.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Long>> apply(@RequestBody ApplyPlanRequest request) {
        Long jobId = jobService.applyPlan(request);
        return ResponseEntity.accepted().body(Map.of(JOB_ID, jobId));
    }

    /**
     * Disabled by default: it reads and writes server-side paths, so
     * {@code PathGuard} (via {@code JobService}) decides whether the request may run at all.
     */
    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Improve a file already on the server",
            description = "Disabled unless SHEETSMITH_PATH_ENDPOINT_ENABLED=true, and both paths must resolve inside a configured root, symlinks followed.")
    @ApiResponse(responseCode = "400", description = "A path that resolves outside the configured roots, symlinks followed.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "403", description = "The endpoint is disabled. It is off unless SHEETSMITH_PATH_ENDPOINT_ENABLED=true.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "500", description = "Enabled without roots configured, which the application refuses to start with.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/improve/path")
    public ResponseEntity<Map<String, Long>> improveByPath(
            @RequestBody @Valid ImproveByPathRequest request) {

        Long jobId = jobService.createAndSubmitByPath(request);
        return ResponseEntity.accepted().body(Map.of(JOB_ID, jobId));
    }
}
