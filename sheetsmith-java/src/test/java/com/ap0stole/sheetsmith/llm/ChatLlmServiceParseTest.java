package com.ap0stole.sheetsmith.llm;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser is the seam where small local models misbehave, so it is tested against the shapes
 * they actually emit rather than the shape we ask for.
 */
class ChatLlmServiceParseTest {

    private ChatLlmService service;

    @BeforeEach
    void setUp() {
        service = new ChatLlmService(null, null, new ObjectMapper());
    }

    @Test
    @DisplayName("reads a well-formed tool call")
    void readsToolCall() {
        AgentDecision decision = service.parse("""
                {"tool": "AGGREGATE", "args": {"range": "A2:D20", "columnIndex": 2, "operation": "SUM"}}
                """);

        assertThat(decision.isToolCall()).isTrue();
        assertThat(decision.tool()).isEqualTo("AGGREGATE");
        assertThat(decision.args())
                .containsEntry("range", "A2:D20")
                .containsEntry("columnIndex", 2)
                .containsEntry("operation", "SUM");
    }

    @Test
    @DisplayName("reads a final answer")
    void readsAnswer() {
        AgentDecision decision = service.parse("{\"answer\": \"Widget A sold most — 1240 units.\"}");

        assertThat(decision.isAnswer()).isTrue();
        assertThat(decision.isToolCall()).isFalse();
        assertThat(decision.answer()).isEqualTo("Widget A sold most — 1240 units.");
    }

    @Test
    @DisplayName("survives markdown fences and chatter around the JSON")
    void stripsSurroundingProse() {
        AgentDecision decision = service.parse("""
                Sure! Here is the next step:
                ```json
                {"tool": "READ_RANGE", "args": {"range": "A1:C3"}}
                ```
                Let me know if that helps.
                """);

        assertThat(decision.tool()).isEqualTo("READ_RANGE");
        assertThat(decision.args()).containsEntry("range", "A1:C3");
    }

    @Test
    @DisplayName("accepts arguments splashed across the root object")
    void readsFlatArgs() {
        AgentDecision decision = service.parse("""
                {"tool": "SORT_DATA", "range": "A2:D20", "columnIndex": 1, "ascending": false}
                """);

        assertThat(decision.tool()).isEqualTo("SORT_DATA");
        assertThat(decision.args())
                .containsEntry("range", "A2:D20")
                .containsEntry("columnIndex", 1)
                .containsEntry("ascending", false);
    }

    @Test
    @DisplayName("accepts 'action' as an alias for 'tool' and drops the model's thinking")
    void readsAliasAndDropsThought() {
        AgentDecision decision = service.parse("""
                {"thought": "I should look at the totals", "action": "EVAL_FORMULA",
                 "formula": "MAX(C2:C20)"}
                """);

        assertThat(decision.tool()).isEqualTo("EVAL_FORMULA");
        assertThat(decision.args()).containsOnlyKeys("formula");
    }

    @Test
    @DisplayName("strips comments some models add inside the JSON")
    void stripsComments() {
        AgentDecision decision = service.parse("""
                {
                  "tool": "FIND_ROWS", // top sellers
                  "args": {"range": "A2:D20", "limit": 3}
                }
                """);

        assertThat(decision.tool()).isEqualTo("FIND_ROWS");
        assertThat(decision.args()).containsEntry("limit", 3);
    }

    @Test
    @DisplayName("an answer wins over a stray tool field")
    void answerWins() {
        AgentDecision decision = service.parse("{\"tool\": null, \"answer\": \"Done.\"}");

        assertThat(decision.isAnswer()).isTrue();
        assertThat(decision.answer()).isEqualTo("Done.");
    }

    @Test
    @DisplayName("reports unusable replies instead of throwing")
    void reportsUnusableReplies() {
        assertThat(service.parse("I cannot do that.").parseError()).isNotBlank();
        assertThat(service.parse("{\"foo\": \"bar\"}").parseError()).isNotBlank();
        assertThat(service.parse("{oh no").parseError()).isNotBlank();

        assertThat(service.parse("I cannot do that.").isToolCall()).isFalse();
        assertThat(service.parse("I cannot do that.").isAnswer()).isFalse();
    }
}
