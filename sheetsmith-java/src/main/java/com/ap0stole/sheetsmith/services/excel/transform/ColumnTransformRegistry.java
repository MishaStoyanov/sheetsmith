package com.ap0stole.sheetsmith.services.excel.transform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place that knows which column transforms exist — the {@link ColumnTransformRegistry}
 * equivalent of {@code ActionRegistry}. A new transform bean reaches the planner prompt, the chat
 * prompt and the handler at once.
 */
@Slf4j
@Service
public class ColumnTransformRegistry {

    private final Map<String, ColumnTransform> transforms;

    public ColumnTransformRegistry(List<ColumnTransform> beans) {
        this.transforms = beans.stream()
                .sorted(Comparator.comparing(ColumnTransform::getType))
                .collect(Collectors.toMap(t -> t.getType().toUpperCase(), Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        log.info("Registered column transforms: {}", transforms.keySet());
    }

    /** Null when the model invented an operation that does not exist. */
    public ColumnTransform find(String operation) {
        return operation == null ? null : transforms.get(operation.trim().toUpperCase());
    }

    public List<String> operations() {
        return List.copyOf(transforms.keySet());
    }

    /** The full spec block for the detailed prompt — every transform documenting itself. */
    public String promptBlock() {
        return transforms.values().stream()
                .map(t -> "   - " + t.promptSpec().strip())
                .collect(Collectors.joining("\n"));
    }

    /**
     * The plain-language reading of one operation, for plan cards and history. Never throws:
     * whatever it returns is shown verbatim in the UI.
     */
    public String describe(String operation, Map<String, Object> options) {
        ColumnTransform transform = find(operation);
        if (transform == null) {
            return operation == null ? "" : operation.toLowerCase().replace('_', ' ');
        }
        try {
            String described = transform.describe(options == null ? Map.of() : options);
            return (described == null || described.isBlank())
                    ? transform.getType().toLowerCase().replace('_', ' ')
                    : described;
        } catch (Exception e) {
            log.warn("describe() failed for transform {}: {}", operation, e.getMessage());
            return transform.getType().toLowerCase().replace('_', ' ');
        }
    }
}
