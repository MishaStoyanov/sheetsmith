package com.ap0stole.sheetsmith.domain.dto;

import java.util.List;

/**
 * Applies a plan that was offered for review.
 *
 * @param planToken the token /api/excel/plan handed back. The plan itself is parked server-side,
 *                  so applying is a reference to what was reviewed rather than a second,
 *                  differently-worded request
 * @param steps     the steps as they stand after review. Editing a range or a formula on a card
 *                  changes what runs, which is the point of reviewing them
 */
public record ApplyPlanRequest(String planToken, List<PlanStepDto> steps) {}
