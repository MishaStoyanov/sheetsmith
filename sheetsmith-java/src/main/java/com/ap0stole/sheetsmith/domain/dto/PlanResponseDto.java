package com.ap0stole.sheetsmith.domain.dto;

import java.util.List;

public record PlanResponseDto(String planToken, List<PlanStepDto> steps) {}
