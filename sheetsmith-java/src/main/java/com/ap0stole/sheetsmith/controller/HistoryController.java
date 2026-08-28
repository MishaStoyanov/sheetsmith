package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.HistorySearchRequest;
import com.ap0stole.sheetsmith.domain.dto.JobHistoryDto;
import com.ap0stole.sheetsmith.services.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "History", description = "Runs, their steps and their files. Whose runs you see depends on your role.")
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final JobService jobService;

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Recent runs",
            description = "Filtered to what the caller may see: their own, plus ordinary users’ for an administrator, plus everything for the superadmin.")
    @GetMapping
    public ResponseEntity<Page<JobHistoryDto>> getHistory(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(jobService.getHistory(pageable));
    }

    /**
     * POST rather than GET because the filters are a body: a dozen optional fields, two of them
     * lists, do not belong in a query string that has to be escaped by hand on the way in and out.
     */
    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Search runs",
            description = "Dates, owner (including no owner), status, keyword, duration, tokens, errors. Sort fields come from an allowlist.")
    @PostMapping("/search")
    public ResponseEntity<Page<JobHistoryDto>> search(@RequestBody(required = false) HistorySearchRequest request) {
        return ResponseEntity.ok(jobService.search(
                request == null ? HistorySearchRequest.unfiltered() : request));
    }

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "One run, with its steps",
            description = "A run the caller may not see is answered as not found rather than forbidden, so the two cannot be told apart.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "No such run — or one this caller may not see, which is deliberately the same answer.",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    })
    @GetMapping("/{id}")
    public ResponseEntity<JobHistoryDto> getJob(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.getById(id));
    }

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Download the result file")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "No such run, or its file has aged out of the retention window.",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    })
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadResult(@PathVariable Long id) {
        Resource file = jobService.downloadResult(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFilename())
                .build());
        return ResponseEntity.ok().headers(headers).body(file);
    }

    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Delete a run",
            description = "The record goes, and its files with it - unless they are revisions of a live session, which belong to that session’s undo history.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "No such run.",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        jobService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }
}
