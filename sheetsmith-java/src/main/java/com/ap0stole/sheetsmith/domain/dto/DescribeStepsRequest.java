package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Asks for the plain-language line of each step to be written again.
 *
 * @param steps the steps as they now stand, after somebody edited them
 */
public record DescribeStepsRequest(@NotEmpty List<PlanStepDto> steps) {
}
