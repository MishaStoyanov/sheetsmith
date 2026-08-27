package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.configs.SecurityConfig;
import com.ap0stole.sheetsmith.configs.SecurityProperties;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatMessageDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatStepDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatTurnDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.services.chat.ChatAgentService;
import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.services.chat.ManualEditService;
import com.ap0stole.sheetsmith.services.chat.ToolInvocation;
import com.ap0stole.sheetsmith.services.chat.TurnListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The streaming endpoint against a scripted agent: what matters is that the wire contract is the
 * one the frontend parses, and that {@code done} carries exactly the body the synchronous endpoint
 * returns — the two must never drift into different answers.
 */
@WebMvcTest(controllers = ChatMessageController.class)
// Printing is off because this endpoint answers from a virtual thread: Spring Boot's default
// result handler iterates the response headers to print them, while the emitter is still setting
// them, and the run dies with a ConcurrentModificationException raised inside the test harness
// rather than by anything under test. It surfaced about one run in seven once a security filter
// chain widened the window.
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
// The real chain, not Spring Boot's default one: without it the slice gets CSRF protection this
// app does not use, and every POST here answers 403 — a failure about the test's own wiring rather
// than about the endpoint.
@Import({ChatConfig.class, SecurityProperties.class, AuthConfig.class, SecurityConfig.class})
class ChatStreamControllerTest {

    private static final String SESSION_ID = "session-1";
    private static final String STREAM_URL = "/api/chat/sessions/" + SESSION_ID + "/messages/stream";
    private static final String SYNC_URL = "/api/chat/sessions/" + SESSION_ID + "/messages";
    private static final String BODY = "{\"text\":\"what is the total?\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatAgentService agentService;

    @MockitoBean
    private DocumentSessionService sessionService;

    @MockitoBean
    private ManualEditService manualEditService;

    private ChatTurnDto turn;

    @BeforeEach
    void setUp() {
        turn = new ChatTurnDto(
                new ChatMessageDto(12L, "ASSISTANT", "1240 in total.",
                        List.of(new ChatStepDto(0, "READ_RANGE", "Read A1:B4", "4 rows",
                                        "{\"range\":\"A1:B4\"}", true, null, false),
                                new ChatStepDto(1, "AGGREGATE", "Summed column B", "1240",
                                        "{\"range\":\"B2:B4\"}", true, null, false)),
                        null, LocalDateTime.of(2026, 8, 13, 9, 30)),
                false, 0);
    }

    @Test
    @DisplayName("every tool call arrives as its own step event while the turn runs")
    void streamsEachStepAsItLands() throws Exception {
        scriptTurn(
                ToolInvocation.ok("READ_RANGE", false, "Read A1:B4", "4 rows", Map.of()),
                ToolInvocation.failed("AGGREGATE", false, "Summed column B", "Column B is empty"));

        List<Frame> frames = stream();

        assertThat(frames).extracting(Frame::event).containsExactly("step", "step", "done");
        assertThat(frames.get(0).data().get("order").asInt()).isZero();
        assertThat(frames.get(0).data().get("tool").asText()).isEqualTo("READ_RANGE");
        assertThat(frames.get(0).data().get("text").asText()).isEqualTo("Read A1:B4");
        assertThat(frames.get(0).data().get("resultPreview").asText()).isEqualTo("4 rows");
        assertThat(frames.get(0).data().get("success").asBoolean()).isTrue();
        assertThat(frames.get(0).data().get("mutating").asBoolean()).isFalse();

        assertThat(frames.get(1).data().get("order").asInt()).isEqualTo(1);
        assertThat(frames.get(1).data().get("success").asBoolean()).isFalse();
        assertThat(frames.get(1).data().get("error").asText()).isEqualTo("Column B is empty");
    }

    @Test
    @DisplayName("the done event is byte-for-byte what POST /messages returns")
    void doneMatchesTheSynchronousBody() throws Exception {
        scriptTurn();
        when(agentService.send(eq(SESSION_ID), anyString())).thenReturn(turn);

        String synchronous = mockMvc.perform(post(SYNC_URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode streamed = stream().stream()
                .filter(frame -> "done".equals(frame.event()))
                .findFirst().orElseThrow().data();

        assertThat(streamed).isEqualTo(objectMapper.readTree(synchronous));
        assertThat(objectMapper.treeToValue(streamed, ChatTurnDto.class)).isEqualTo(turn);
    }

    @Test
    @DisplayName("a turn that throws ends the stream with one error event, not a dead socket")
    void reportsFailureAsAnErrorEvent() throws Exception {
        when(agentService.send(eq(SESSION_ID), anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.PROCESSING_ERROR, "Chat failed: model unreachable"));

        List<Frame> frames = stream();

        assertThat(frames).singleElement().satisfies(frame -> {
            assertThat(frame.event()).isEqualTo("error");
            assertThat(frame.data().get("message").asText()).isEqualTo("Chat failed: model unreachable");
        });
    }

    /** Answers the listener-taking overload by replaying the given calls, then returning the turn. */
    private void scriptTurn(ToolInvocation... invocations) {
        when(agentService.send(eq(SESSION_ID), anyString(), any())).thenAnswer(call -> {
            TurnListener listener = call.getArgument(2);
            for (int i = 0; i < invocations.length; i++) {
                listener.onStep(invocations[i], i);
            }
            return turn;
        });
    }

    private List<Frame> stream() throws Exception {
        MvcResult result = mockMvc.perform(post(STREAM_URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // Waits for the emitter to complete, so the recorded response holds the whole stream.
        mockMvc.perform(asyncDispatch(result));

        return parse(result.getResponse().getContentAsString());
    }

    /** The frontend hand-parses these frames, so the test does too rather than trusting a helper. */
    private List<Frame> parse(String body) throws Exception {
        List<Frame> frames = new ArrayList<>();
        for (String block : body.replace("\r\n", "\n").split("\n\n")) {
            String event = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
            if (event != null) {
                frames.add(new Frame(event, objectMapper.readTree(data.toString())));
            }
        }
        return frames;
    }

    private record Frame(String event, JsonNode data) {
    }
}
