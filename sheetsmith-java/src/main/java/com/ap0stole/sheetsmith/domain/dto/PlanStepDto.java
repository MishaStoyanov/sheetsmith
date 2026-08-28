package com.ap0stole.sheetsmith.domain.dto;

import java.util.Map;

/**
 * A step of a proposed plan.
 *
 * @param index       its place in the order the steps run
 * @param type        the action, from the catalogue this instance supports
 * @param properties  the action's own arguments — ranges, formats, formulas
 * @param description the plain-language line the review card shows. Recomputed whenever the
 *                    properties are edited, so a card never describes a range the step no longer
 *                    targets
 */
public record PlanStepDto(int index, String type, Map<String, Object> properties, String description) {
}
