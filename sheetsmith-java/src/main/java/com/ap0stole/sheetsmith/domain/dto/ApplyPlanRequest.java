package com.ap0stole.sheetsmith.domain.dto;

import java.util.List;

public record ApplyPlanRequest(String planToken, List<PlanStepDto> steps) {}
