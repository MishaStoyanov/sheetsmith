package com.ap0stole.sheetsmith.llm;

import java.util.Map;

/**
 * One reply from the chat model: either a tool to run, a final answer, or a malformed reply
 * we hand back to the model so it can correct itself.
 */
public record AgentDecision(String tool, Map<String, Object> args, String answer, String parseError) {

    public static AgentDecision toolCall(String tool, Map<String, Object> args) {
        return new AgentDecision(tool, args == null ? Map.of() : args, null, null);
    }

    public static AgentDecision answer(String answer) {
        return new AgentDecision(null, Map.of(), answer, null);
    }

    public static AgentDecision unparseable(String parseError) {
        return new AgentDecision(null, Map.of(), null, parseError);
    }

    public boolean isAnswer() {
        return answer != null && !answer.isBlank();
    }

    public boolean isToolCall() {
        return tool != null && !tool.isBlank();
    }
}
