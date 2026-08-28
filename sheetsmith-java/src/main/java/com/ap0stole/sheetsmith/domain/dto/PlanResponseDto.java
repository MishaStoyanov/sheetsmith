package com.ap0stole.sheetsmith.domain.dto;

import java.util.List;

/**
 * A plan offered for review, and the token that applies it.
 *
 * @param planToken what /api/excel/apply takes. The plan is parked server-side until then, so
 *                  applying it cannot be a second, differently-worded request
 * @param steps     the steps in the order they would run, each described in plain language
 */
public record PlanResponseDto(String planToken, List<PlanStepDto> steps) {}
