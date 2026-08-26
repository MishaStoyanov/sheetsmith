package com.ap0stole.sheetsmith.services.excel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place that knows which action handlers exist. The improve pipeline, the chat and the
 * plan-review UI all look them up here, so a new handler bean reaches every surface at once.
 */
@Slf4j
@Service
public class ActionRegistry {

    private final Map<String, ActionHandler> handlers;

    public ActionRegistry(List<ActionHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(h -> h.getType().toUpperCase(), Function.identity()));
        log.info("Registered action handlers: {}", handlers.keySet());
    }

    /** Null when the LLM invented an action that does not exist. */
    public ActionHandler find(String type) {
        return type == null ? null : handlers.get(type.trim().toUpperCase());
    }

    public Set<String> types() {
        return handlers.keySet();
    }

    public int size() {
        return handlers.size();
    }

    /** The past-tense reading — what chat and history show once a step has run. */
    public String describe(String type, Map<String, Object> properties) {
        return describe(type, properties, StepTense.PAST);
    }

    /**
     * The plain-language line users see for a step, in plan cards, chat and history alike.
     * Never throws and never returns an enum — it is shown verbatim in the UI.
     */
    public String describe(String type, Map<String, Object> properties, StepTense tense) {
        ActionHandler handler = find(type);
        if (handler == null) {
            return type == null ? "Unknown step" : type.toLowerCase().replace('_', ' ');
        }
        try {
            String described = handler.describe(properties == null ? Map.of() : properties, tense);
            return (described == null || described.isBlank())
                    ? type.toLowerCase().replace('_', ' ')
                    : described;
        } catch (Exception e) {
            log.warn("describe() failed for {}: {}", type, e.getMessage());
            return type.toLowerCase().replace('_', ' ');
        }
    }
}
