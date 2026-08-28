package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.DocumentSessionDto;
import com.ap0stole.sheetsmith.domain.dto.chat.*;
import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.services.chat.ManualEditService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A session and its working copy: upload, describe, download, hand-edit, undo, delete.
 * <p>
 * <strong>Every handler that takes a session id is guarded by it.</strong> None of them were: an id
 * was enough to read, edit, revert or download somebody else's document. The rule sits on the
 * handlers rather than deeper because a job continues on a virtual thread that carries no security
 * context — asking there would answer "nobody" and refuse the caller their own work.
 * <p>
 * Despite the path, this is <em>not</em> the chat — it is the shared document workspace, and the
 * improve flow uploads into it and reads its revisions. The two endpoints that actually talk to a
 * model live in {@link ChatMessageController}, which disappears when the chat is turned off; these
 * stay, because without them there is no product. The path is historical, from when the chat
 * introduced sessions.
 */
@Slf4j
@Tag(name = "Sessions", description = "A spreadsheet open on a desk: upload, describe, download, hand-edit, undo. Despite the path, this is the shared document workspace rather than the chat.")
@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DocumentSessionService sessionService;
    private final ManualEditService manualEditService;

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Open a document",
            description = "Uploads a workbook and starts a session: a working copy with a revision chain, which both the chat and the improve flow write to.")
    @PostMapping
    public ResponseEntity<DocumentSessionDto> create(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(sessionService.create(file));
    }

    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "Describe the document",
            description = "Sheets, schema, current revision. Somebody else’s session is refused.")
    @GetMapping("/{sessionId}")
    public ResponseEntity<DocumentSessionDto> get(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.describe(sessionId));
    }

    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "The conversation so far",
            description = "Each answer carries the chain of steps that produced it.")
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageDto>> history(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.history(sessionId));
    }

    /** The current working copy — the frontend re-fetches this whenever a turn changed the sheet. */
    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "Download the current working copy")
    @GetMapping("/{sessionId}/file")
    public ResponseEntity<Resource> file(@PathVariable String sessionId) {
        Resource resource = sessionService.currentFile(sessionId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_MIME));
        headers.setContentDisposition(ContentDisposition.inline().filename(resource.getFilename()).build());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    /** Cells the user typed in the grid, committed as one revision so they survive a refresh. */
    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "Commit cells typed in the grid",
            description = "One revision for the batch, so hand edits survive a refresh and can be undone like any other change.")
    @PostMapping("/{sessionId}/edits")
    public ResponseEntity<Map<String, Integer>> edits(@PathVariable String sessionId,
                                                      @RequestBody @Valid CellEditsRequest request) {
        return ResponseEntity.ok(Map.of("revision", manualEditService.apply(sessionId, request)));
    }

    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "Go back to an earlier revision")
    @PostMapping("/{sessionId}/revert")
    public ResponseEntity<Map<String, Integer>> revert(@PathVariable String sessionId,
                                                       @RequestBody @Valid RevertRequest request) throws IOException {
        int revision = sessionService.revert(sessionId, request.revision());
        return ResponseEntity.ok(Map.of("revision", revision));
    }

    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Delete the session and its revisions. Superadmin only")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        sessionService.delete(sessionId);
        return ResponseEntity.noContent().build();
    }
}
