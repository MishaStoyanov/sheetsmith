package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import org.springframework.stereotype.Component;

/**
 * The action catalog as the models actually see it, assembled once at startup.
 * <p>
 * Most of it is the static text in {@link ActionCatalog}, but TRANSFORM_COLUMN's rule list is built
 * from the beans that exist, so adding a {@code ColumnTransform} reaches the planner and the chat
 * without either prompt being edited. Both callers inject this rather than the constants.
 */
@Component
public class ActionCatalogPrompt {

    private final String mutatingActions;
    private final String mutatingActionsIndex;

    public ActionCatalogPrompt(ColumnTransformRegistry transforms) {
        this.mutatingActions = ActionCatalog.MUTATING_ACTIONS
                + "\n"
                + ActionCatalog.TRANSFORM_COLUMN_TEMPLATE.formatted(transforms.promptBlock());

        this.mutatingActionsIndex = ActionCatalog.MUTATING_ACTIONS_INDEX_LIST
                + ActionCatalog.TRANSFORM_COLUMN_INDEX_TEMPLATE
                .formatted(String.join("|", transforms.operations()))
                + ActionCatalog.MUTATING_ACTIONS_INDEX_NOTE;
    }

    /** Full rules for every mutating action — the planner's system prompt and the chat's escalation. */
    public String mutatingActions() {
        return mutatingActions;
    }

    /** One line per action: enough to choose one, not enough to run it. */
    public String mutatingActionsIndex() {
        return mutatingActionsIndex;
    }
}
