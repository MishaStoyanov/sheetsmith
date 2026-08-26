package com.ap0stole.sheetsmith.domain.dto;

import java.util.Map;

/**
 * A step of a proposed plan. {@code description} is the plain-language line the review card shows;
 * it is recomputed whenever the user edits the properties, so the card never describes a range the
 * step no longer targets.
 */
public record PlanStepDto(int index, String type, Map<String, Object> properties, String description) {
}
