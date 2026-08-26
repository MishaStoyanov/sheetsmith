package com.ap0stole.sheetsmith.services.excel.transform;

import java.util.Map;
import java.util.Optional;

/**
 * One rewriting rule applied to every cell of a column by {@code TRANSFORM_COLUMN}.
 * <p>
 * The model never sees the values — it names the rule and Java runs it over however many rows there
 * are, which is why a 35 000-row column costs the same prompt as a 3-row one. Adding a rule is one
 * {@code @Component}: {@link ColumnTransformRegistry} discovers it and {@link #promptSpec()} writes
 * it into both system prompts.
 */
public interface ColumnTransform {

    String getType();

    /**
     * The rewritten value, or empty to leave the cell exactly as it was.
     * <p>
     * Empty is the honest answer for anything the rule does not understand — a nine-digit phone
     * number, a blank cell, text where a number was expected. Those are counted and reported as
     * skipped, because a transform that quietly mangles what it cannot parse is worse than one
     * that admits the miss.
     */
    Optional<String> apply(String value, Map<String, Object> options);

    /** How this rule documents itself in the system prompt, one entry per rule. */
    String promptSpec();

    /** Noun phrase completing "Rewrite C2:C500 …", e.g. "as +1 (XXX) XXX-XXXX phone numbers". */
    String describe(Map<String, Object> options);

    /** True when the result should be written as a real number rather than as text. */
    default boolean numeric() {
        return false;
    }
}
