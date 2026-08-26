package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record DescribeStepsRequest(@NotEmpty List<PlanStepDto> steps) {
}
