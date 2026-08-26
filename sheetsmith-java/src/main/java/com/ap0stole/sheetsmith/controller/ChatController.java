package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.chat.*;
import com.ap0stole.sheetsmith.services.chat.ChatSessionService;
import com.ap0stole.sheetsmith.services.chat.ManualEditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A session and its working copy: upload, describe, download, hand-edit, undo, delete.
 * <p>
 * Despite the path, this is <em>not</em> the chat — it is the shared document workspace, and the
 * improve flow uploads into it and reads its revisions. The two endpoints that actually talk to a
 * model live in {@link ChatMessageController}, which disappears when the chat is turned off; these
 * stay, because without them there is no product. The path is historical, from when the chat
 * introduced sessions.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ChatSessionService sessionService;
    private final ManualEditService manualEditService;

    @PostMapping
    public ResponseEntity<ChatSessionDto> create(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(sessionService.create(file));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ChatSessionDto> get(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.describe(sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageDto>> history(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.history(sessionId));
    }

    /** The current working copy — the frontend re-fetches this whenever a turn changed the sheet. */
    @GetMapping("/{sessionId}/file")
    public ResponseEntity<Resource> file(@PathVariable String sessionId) {
        Resource resource = sessionService.currentFile(sessionId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_MIME));
        headers.setContentDisposition(ContentDisposition.inline().filename(resource.getFilename()).build());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    /** Cells the user typed in the grid, committed as one revision so they survive a refresh. */
    @PostMapping("/{sessionId}/edits")
    public ResponseEntity<Map<String, Integer>> edits(@PathVariable String sessionId,
                                                      @RequestBody @Valid CellEditsRequest request) {
        return ResponseEntity.ok(Map.of("revision", manualEditService.apply(sessionId, request)));
    }

    @PostMapping("/{sessionId}/revert")
    public ResponseEntity<Map<String, Integer>> revert(@PathVariable String sessionId,
                                                       @RequestBody @Valid RevertRequest request) throws IOException {
        int revision = sessionService.revert(sessionId, request.revision());
        return ResponseEntity.ok(Map.of("revision", revision));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        sessionService.delete(sessionId);
        return ResponseEntity.noContent().build();
    }
}
