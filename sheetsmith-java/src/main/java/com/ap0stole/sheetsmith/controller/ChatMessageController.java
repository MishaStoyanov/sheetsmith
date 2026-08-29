package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatStepDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatTurnDto;
import com.ap0stole.sheetsmith.domain.dto.chat.SendMessageRequest;
import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.services.chat.ChatAgentService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * The two endpoints that send anything to a language model on a user's behalf.
 * <p>
 * They live apart from {@link ChatController} because the rest of {@code /api/chat/sessions} is not
 * the chat at all — it is the shared document workspace the improve flow uploads into and reads
 * revisions from, and it has to keep working on an instance with no chat. Splitting them is what
 * lets {@code sheetsmith.chat.enabled=false} remove the model-facing surface without taking the
 * product with it.
 */
@Slf4j
@Tag(name = "Chat", description = "The two endpoints that actually talk to a model. Absent entirely on an instance running with the chat off.")
@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class ChatMessageController {

    private final ChatAgentService agentService;
    private final ChatConfig chatConfig;
    private final DocumentSessionService sessionService;

    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "Send a message and wait for the answer",
            description = "A turn is a chain of tool calls; the reply carries the steps that produced it.")
    @ApiResponse(responseCode = "402", description = "The spend limit for this account is used up.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "404", description = "No such session, or it has expired.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "502", description = "The model could not be reached or answered unusably.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<ChatTurnDto> send(@PathVariable String sessionId,
                                            @RequestBody @Valid SendMessageRequest request) {
        return ResponseEntity.ok(agentService.send(sessionId, request.text().trim()));
    }

    /**
     * The same turn as above, narrated. A turn is a chain of tool calls that can run for a minute
     * against a local model, and those calls are the only evidence anything is happening — so they
     * go out as {@code step} events, then the finished {@link ChatTurnDto} as {@code done}.
     */
    @PreAuthorize("@access.maySeeSession(#sessionId)")
    @Operation(summary = "The same turn, narrated as it happens",
            description = "Server-sent events: each tool call arrives as a step, then the finished turn as done. A turn against a local model can run for a minute, and the steps are the only evidence anything is happening.")
    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestBody @Valid SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(chatConfig.getStreamTimeoutMs());
        String text = request.text().trim();

        // Virtual thread, as JobService does: the request thread must not be parked for the turn.
        Thread.ofVirtual().start(() -> runStream(emitter, sessionId, text));
        return emitter;
    }

    private void runStream(SseEmitter emitter, String sessionId, String text) {
        try {
            ChatTurnDto turn = agentService.send(sessionId, text,
                    (invocation, order) -> emit(emitter, "step", ChatStepDto.live(invocation, order)));
            emit(emitter, "done", turn);
        } catch (Exception e) {
            log.error("Streamed chat turn failed for session {}", sessionId, e);
            emit(emitter, "error", Map.of("message", e.getMessage() == null ? "Chat failed" : e.getMessage()));
        } finally {
            emitter.complete();
        }
    }

    /**
     * A browser that navigated away is the ordinary way a send fails, and by then the turn holds the
     * session lock mid-edit — dropping the event is right, aborting the turn would leave half a change.
     */
    private void emit(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE '{}' event undeliverable: {}", name, e.getMessage());
        }
    }
}
